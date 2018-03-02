package org.jfw.jina.ssl.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.server.HttpService;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2FlagsUtil;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.impl.Http2FrameReader;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.SslUtil;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.impl.QueueProviderImpl.LinkedNode;

public class SslHttp2CheckChannel implements NioAsyncChannel, KeepAliveCheck, TaskCompletionHandler {
	private static final Logger LOG = LogFactory.getLog(SslHttp2CheckChannel.class);

	protected String channelId;
	protected Http2AsyncExecutor executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
	protected TagQueue<ByteBuffer, TaskCompletionHandler> outputCache;
	protected Throwable writeException;
	int ridx;
	int widx;
	ByteBuffer rbuffer;
	byte[] rBytes;
	int rlen;
	protected DNode keepAliveNode;
	private long keepAliveTimeout = Long.MAX_VALUE;

	private boolean prefaced = true;
	private Http2Settings setting;
	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;
	protected int sslRidx;
	protected int sslWidx;
	private JdkSslEngine wrapEngine;
	private SSLEngine engine;

	private Http2Settings remoteConfig;
	private int packetLen = 0;
	private HttpService service;

	private NioAsyncChannel delegatedChannel = null;

	public SslHttp2CheckChannel(Http2AsyncExecutor executor, SocketChannel javaChannel, SslAsyncChannel sslChannel, Http2Settings setting) {
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.setting = setting;
		this.rbuffer = ByteBuffer.allocate(8192);
		this.rBytes = rbuffer.array();
		this.rlen = this.rBytes.length;
		this.ridx = this.widx = 0;
		this.key = sslChannel.getSelectionKey();
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslRidx = sslChannel.getSslRidx();
		this.sslWidx = sslChannel.getSslWidx();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
		this.keepAliveNode = this.executor.newDNode(this);
		this.outputCache = sslChannel.getOutCache();
		assert LOG.debug(sslChannel.getChannelId() + " outputCache.isEmpty:" + outputCache.isEmpty() + ",sslRidx:" + sslRidx + ",sslWidx:" + this.sslWidx);
	}

	public HttpService getService() {
		return service;
	}

	public void setService(HttpService service) {
		this.service = service;
	}

	@Override
	public void setChannelId(String id) {
		this.channelId = id;
	}

	private void unwrap() {
		for (;;) {
			int dl = this.sslWidx - this.sslRidx;
			assert LOG.debug(this.channelId + " packetLen == " + this.packetLen + " dl == " + dl);
			if (this.packetLen > 0) {
				if (dl < this.packetLen) {
					break;
				}
			} else {
				if (dl < SslUtil.SSL_RECORD_HEADER_LENGTH) {
					break;
				}
				int pl = SslUtil.getEncryptedPacketLength(this.sslReadBuffer, this.sslRidx);
				if (pl == SslUtil.NOT_ENCRYPTED) {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " invalid ssl record length:" + pl);
					}
					this.writeException = new RuntimeException("invalid ssl record length");
					this.close();
					return;
				} else {
					this.packetLen = pl;
					if (dl < pl) {
						break;
					}
				}
			}
			try {
				this.executor.unwrap(this.engine, this.sslReadBuffer, this.sslRidx, packetLen);
			} catch (Exception e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " unwrap error", e);
				}
				this.writeException = e;
				this.close();
				return;
			}
			this.packetLen = 0;
			this.sslRidx += executor.bytesConsumed;
			int produced = executor.bytesProduced;
			if (produced > 0) {
				this.ensureCapacity4ReadBuffer(produced);
				this.rbuffer.put(executor.sslByteArray, 0, produced);
				this.widx += produced;
			}
			assert LOG.debug(this.rbuffer, this.ridx, this.widx);
		}
		int len = this.widx - this.ridx;
		if (len > 0) {
			this.handleRead(len);
		} else {
			this.addKeepAliveCheck();
		}
	}

	private void handleRead(int len) {
		assert LOG.debug(this.rbuffer, this.ridx, this.widx);
		if (this.prefaced) {
			if (len >= HttpConsts.HTTP2_PREFACE.length) {
				assert LOG.debug(this.channelId + " read prefaced");
				int pidx = this.ridx;
				for (int i = 0; i < HttpConsts.HTTP2_PREFACE.length; ++i) {
					if (HttpConsts.HTTP2_PREFACE[i] != this.rBytes[pidx++]) {
						if (LOG.enableWarn()) {
							LOG.warn(this.channelId + " http2 prefaced error");
						}
						this.close();
						return;
					}
				}
				this.ridx += HttpConsts.HTTP2_PREFACE.length;
				len -= HttpConsts.HTTP2_PREFACE.length;
				assert LOG.debug(this.channelId + " read prefaced success");
				this.prefaced = false;
			} else {
				this.addKeepAliveCheck();
				assert LOG.debug(this.channelId + " read prefaced fail:prefaced  to small");
				return;
			}
		}
		if (len >= 9) {
			int pos = this.ridx;
			int pl = (this.rBytes[pos++] & 0xff) << 16 | ((this.rBytes[pos++] & 0xff) << 8) | (this.rBytes[pos++] & 0xff);
			assert LOG.debug(this.channelId + " read setting frame payload length:" + pl);
			if (this.rBytes[pos++] != Http2FrameReader.FRAME_TYPE_SETTINGS) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " fist frame type is not a SETTING");
				}
				this.close();
				return;
			}
			len -= 9;
			if (len >= pl) {
				this.ridx += 9;
				Http2Settings remoteSettings = this.parseSetting(pl);
				if (remoteSettings == null) {
					this.close();
					return;
				}
				this.ridx += pl;
				ByteBuffer sendB = this.buildInitSend();
				if (sendB == null) {
					this.close();
					return;
				}
				this.remoteConfig = remoteSettings;
				this.write(sendB);
			} else {
				this.addKeepAliveCheck();
			}
		}
	}

	private void switchHandler() {
		assert LOG.debug(this.channelId + " invoke swichHandler");
		SslHttp2ServerConnection sslChannel = new SslHttp2ServerConnection(this.executor, javaChannel, this.setting, this);
		sslChannel.setService(service);
		this.delegatedChannel = sslChannel;
		this.key.attach(sslChannel);
		sslChannel.setChannelId(this.channelId);
		sslChannel.addKeepAliveCheck();
		sslChannel.applySetting(this.remoteConfig);
		if (this.sslWidx > this.sslRidx) {
			sslChannel.read();
		} else if (this.widx > this.ridx) {
			assert LOG.debug(this.rbuffer, this.ridx, this.widx);
			sslChannel.handleRead(this.widx - this.ridx);
		}
	}

	private Http2Settings parseSetting(int size) {
		int numSettings = size / Http2FrameReader.FRAME_SETTING_SETTING_ENTRY_LENGTH;
		Http2Settings settings = new Http2Settings();
		for (int index = 0, setIdx = this.ridx; index < numSettings; ++index) {
			char id = (char) (((this.rBytes[setIdx++] << 8) | (this.rBytes[setIdx++] & 0xFF)) & 0xffff);
			long value = 0xffffffffL & (((this.rBytes[setIdx++] & 0xff) << 24) | ((this.rBytes[setIdx++] & 0xff) << 16) | ((this.rBytes[setIdx++] & 0xff) << 8)
					| (this.rBytes[setIdx++] & 0xff));
			try {
				if (id == Http2Settings.SETTINGS_HEADER_TABLE_SIZE) {
					settings.headerTableSize(value);
				} else if (id == Http2Settings.SETTINGS_ENABLE_PUSH) {
					if (value != 0L && value != 1L) {
						throw new IllegalArgumentException("Setting ENABLE_PUSH is invalid: " + value);
					}
					settings.pushEnabled(1 == value);
				} else if (id == Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS) {
					settings.maxConcurrentStreams(value);
				} else if (id == Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE) {
					settings.initialWindowSize(value);
				} else if (id == Http2Settings.SETTINGS_MAX_FRAME_SIZE) {
					settings.maxFrameSize(value);
				} else if (id == Http2Settings.SETTINGS_MAX_HEADER_LIST_SIZE) {
					settings.maxHeaderListSize(value);
				}
			} catch (IllegalArgumentException e) {
				LOG.error(this.channelId + " parse http2 SETTING error", e);
				return null;
			}
		}
		assert LOG.debug(this.channelId + "parse http2 SEETING:" + settings);
		return settings;
	}

	private ByteBuffer buildInitSend() {
		assert LOG.debug(this.channelId + "local http2 SETTING:" + setting.toString());
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		buffer.order(ByteOrder.BIG_ENDIAN);
//		buffer.put(HttpConsts.HTTP2_PREFACE);
		int len = setting.writeToFrameBuffer(executor.ouputCalcBuffer, 0);
		buffer.put(executor.ouputCalcBuffer, 0, len);
		buffer.put((byte) 0);
		buffer.put((byte) 0);
		buffer.put((byte) 0);
		buffer.put(Http2FrameReader.FRAME_TYPE_SETTINGS);
		buffer.put(Http2FlagsUtil.ACK);
		buffer.putInt(0);
		buffer.flip();
		try {
			executor.wrap(engine, buffer);
		} catch (Throwable e) {
			this.writeException = e;
			return null;
		}
		buffer.clear();
		buffer.put(executor.sslByteArray, 0, executor.bytesProduced);
		buffer.flip();
		return buffer;
	}

	@Override
	public void read() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.read();
			return;
		}
		this.removeKeepAliveCheck();
		int len = 0;
		for (;;) {
			try {
				len = this.javaChannel.read(sslReadBuffer);
			} catch (Throwable e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " read os buffer error", e);
				}
				this.close();
				return;
			}
			if (len == 0) {
				break;
			} else if (len < 0) {
				unwrap();
				if (this.writeException == null) {
					this.cleanOpRead();
					this.handleInputClose();
				}
				return;
			}
			this.sslWidx += len;
			if (this.sslWidx >= sslCapacity) {
				if (this.sslRidx > 0) {
					this.compactSslReadBuffer();
				} else {
					this.extendSslReadBuffer();
				}
			}
		}
		unwrap();
	}

	private void handleInputClose() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.read();
			return;
		}
		this.close();
	}

	protected void write(ByteBuffer buffer) {
		if (this.writeException == null) {
			if (outputCache.isEmpty()) {
				try {
					this.javaChannel.write(buffer);
				} catch (IOException e) {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " write data error", e);
					}
					this.writeException = e;
					this.close();
					return;
				}
				if (buffer.hasRemaining()) {
					outputCache.offer(buffer, this);
					this.setOpWrite();
				} else {
					this.completed(this.executor);
				}
			} else {
				outputCache.offer(buffer, this);
			}
		}
	}

	@Override
	public void write() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.write();
			return;
		}
		for (;;) {
			TagNode tagNode = outputCache.peekTagNode();
			if (tagNode == null) {
				if (this.key != null) {
					this.cleanOpWrite();
				}
				return;
			}
			ByteBuffer buf = tagNode.item();
			TaskCompletionHandler task = tagNode.tag();
			if (buf.hasRemaining()) {
				try {
					this.javaChannel.write(buf);
				} catch (IOException e) {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " write data error", e);
					}
					this.writeException = e;
					this.close();
					return;
				}
				if (buf.hasRemaining()) {
					return;
				}
			}
			outputCache.unsafeShift();
			if (task != null) {
				executor.safeInvokeCompleted(task);
			}
		}
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		this.key = key;
	}

	@Override
	public void close() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.close();
		} else {
			this.removeKeepAliveCheck();
			if (this.keepAliveNode != null) {
				((LinkedNode) this.keepAliveNode).item = null;
				this.executor.freeDNode(this.keepAliveNode);
				this.keepAliveNode = null;
			}
			this.closeJavaChannel();
		}
	}

	private void closeJavaChannel() {
		SocketChannel jc = this.javaChannel;
		SelectionKey k = this.key;
		this.javaChannel = null;
		this.key = null;
		if (k != null) {
			try {
				k.interestOps(0);
				k.cancel();
			} catch (Exception e) {
			}
		}
		if (jc != null) {
			try {
				jc.close();
			} catch (Throwable t) {

			}
		}
		this.outputCache.clear(Handler.NOOP);
	}

	@Override
	public void connected() {
		throw new IllegalStateException();
	}

	@Override
	public long getKeepAliveTime() {
		return this.keepAliveTimeout;
	}

	@Override
	public void keepAliveTimeout() {
		assert LOG.debug(this.channelId + "invoke KeepAliveTimeout()");
		this.close();
	}

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue(this.executor.getKeepAliveQueue());
			assert LOG.debug(this.channelId + " invoke removeKeepAliveCheck() return true");
			return true;
		}
		assert LOG.debug(this.channelId + " invoke removeKeepAliveCheck() return false");
		return false;
	}

	public boolean addKeepAliveCheck() {
		if (this.keepAliveTimeout == Long.MAX_VALUE) {
			this.keepAliveTimeout = System.currentTimeMillis();
			this.keepAliveNode.enqueue(this.executor.getKeepAliveQueue());
			assert LOG.debug(this.channelId + " executor.getKeepAliveQueue().isEmpty():" + this.executor.getKeepAliveQueue().isEmpty());
			assert LOG.debug(this.channelId + " invoke addKeepAliveCheck() return true");
			return true;
		}
		assert LOG.debug(this.channelId + " invoke addKeepAliveCheck() return false");
		return false;
	}

	protected final void setOpRead() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_READ) == 0) {
			key.interestOps(interestOps | SelectionKey.OP_READ);
		}
	}

	protected final void cleanOpRead() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_READ) != 0) {
			key.interestOps(interestOps & ~SelectionKey.OP_READ);
		}
	}

	protected final void setOpWrite() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) == 0) {
			key.interestOps(interestOps | SelectionKey.OP_WRITE);
		}
	}

	protected final void cleanOpWrite() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) != 0) {
			key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
		}
	}

	protected void ensureCapacity4ReadBuffer(int increment) {
		int nlen = this.widx + increment;
		if (nlen > this.rlen) {
			nlen -= this.ridx;
			if (nlen > this.rlen) {
				byte[] obs = this.rBytes;
				while (nlen > this.rlen) {
					this.rlen += 8192;
				}
				this.rbuffer = ByteBuffer.allocate(this.rlen);
				this.rbuffer.order(ByteOrder.BIG_ENDIAN);
				this.rBytes = this.rbuffer.array();
				this.widx -= this.ridx;
				this.rbuffer.put(obs, this.ridx, this.widx);
				this.ridx = 0;
			} else {
				this.compactReadBuffer();
			}
		}
	}

	private void compactSslReadBuffer() {
		if (this.sslRidx == this.sslWidx) {
			this.sslReadBuffer.clear();
		} else if (this.sslRidx > 0) {
			this.sslReadBuffer.flip();
			this.sslReadBuffer.position(this.sslRidx);
			this.sslReadBuffer.compact();
			this.sslWidx = this.sslWidx - this.sslRidx;
			this.sslRidx = 0;
			this.sslReadBuffer.limit(this.sslCapacity).position(sslWidx);
		}
	}

	private void extendSslReadBuffer() {
		this.sslCapacity += 4096;
		ByteBuffer buffer = ByteBuffer.allocate(sslCapacity);
		buffer.put((ByteBuffer) sslReadBuffer.flip().position(this.sslRidx));
		this.sslWidx = this.sslWidx - this.sslRidx;
		this.sslRidx = 0;
		this.sslReadBuffer = buffer;
	}

	protected void compactReadBuffer() {
		if (this.ridx > 0) {
			if (ridx == this.widx) {
				this.rbuffer.clear();
				this.ridx = this.widx = 0;
			} else {
				this.widx -= this.ridx;
				System.arraycopy(this.rBytes, this.ridx, this.rBytes, 0, this.widx);
				this.ridx = 0;
				this.rbuffer.clear().position(this.widx);
			}
		}
	}

	@Override
	public void completed(AsyncExecutor executor) {
		this.removeKeepAliveCheck();
		if (this.keepAliveNode != null) {
			((LinkedNode) this.keepAliveNode).item = null;
			this.executor.freeDNode(this.keepAliveNode);
			this.keepAliveNode = null;
		}
		this.switchHandler();
	}

	@Override
	public void failed(Throwable exc, AsyncExecutor executor) {
	}

	public SelectionKey getSelectionKey() {
		return this.key;
	}

	public ByteBuffer getSslReadBuffer() {
		return this.sslReadBuffer;
	}

	public int getSslRidx() {
		return this.sslRidx;
	}

	public int getSslWidx() {
		return this.sslWidx;
	}

	public JdkSslEngine getWrapSslEngine() {
		return this.wrapEngine;
	}

	public TagQueue<ByteBuffer, TaskCompletionHandler> getOutCache() {
		return this.outputCache;
	}
}
