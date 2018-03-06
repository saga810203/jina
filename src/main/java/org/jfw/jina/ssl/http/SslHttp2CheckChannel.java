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
	protected Throwable transferException;
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
	private JdkSslEngine wrapEngine;
	private SSLEngine engine;
	private boolean readSslHeader = true;
	private Http2Settings remoteConfig;
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
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.readSslHeader = true;
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
		this.keepAliveNode = this.executor.newDNode(this);
		this.outputCache = sslChannel.getOutCache();
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

	private void setTransferException(Throwable e, boolean read, boolean ssl) {
		if (LOG.enableWarn()) {
			LOG.warn(this.channelId + " network or ssl error{read:" + read + ",ssl:" + ssl + "}:", e);
		}
		this.transferException = e;
		this.close();
	}

	private boolean unwrap() {
		assert LOG.assertDebug(this.channelId, " invoke unwrap()");
		try {
			this.executor.unwrap(this.engine, this.sslReadBuffer);
		} catch (Exception e) {
			setTransferException(new RuntimeException("unwrap error"), false, true);
			return false;
		}
		int produced = executor.bytesProduced;
		if (produced > 0) {
			this.ensureCapacity4ReadBuffer(produced);
			this.rbuffer.put(executor.sslByteArray, 0, produced);
			this.widx += produced;
		}
		return true;
	}

	private boolean handleRead() {
		assert LOG.assertDebug(this.channelId + " invoke handleRead()");
		this.removeKeepAliveCheck();
		int len = this.widx - this.ridx;
		if (this.prefaced) {
			if (len >= HttpConsts.HTTP2_PREFACE.length) {
				assert LOG.assertTrace("read prefaced");
				int pidx = this.ridx;
				for (int i = 0; i < HttpConsts.HTTP2_PREFACE.length; ++i) {
					if (HttpConsts.HTTP2_PREFACE[i] != this.rBytes[pidx++]) {
						this.setTransferException(new IllegalStateException("invalid http prefaced"), true, false);
						return true;
					}
				}
				this.ridx += HttpConsts.HTTP2_PREFACE.length;
				len -= HttpConsts.HTTP2_PREFACE.length;
				assert LOG.assertTrace("read prefaced success");
				this.prefaced = false;
			} else {
				this.addKeepAliveCheck();
				assert LOG.assertTrace("read prefaced fail:prefaced  to small");
				return false;
			}
		}
		if (len >= 9) {
			int pos = this.ridx;
			int pl = (this.rBytes[pos++] & 0xff) << 16 | ((this.rBytes[pos++] & 0xff) << 8) | (this.rBytes[pos++] & 0xff);
			assert LOG.assertTrace("read setting frame payload length:" + pl);
			byte frameType = this.rBytes[pos++];
			if (frameType != Http2FrameReader.FRAME_TYPE_SETTINGS) {
				this.setTransferException(new IllegalStateException("invalid http2 frame type:" + frameType), true, false);
				return true;
			}
			len -= 9;
			if (len >= pl) {
				this.ridx += 9;
				Http2Settings remoteSettings = this.parseSetting(pl);
				if (remoteSettings == null) {
					this.setTransferException(new IllegalStateException("invalid http2 frame(SETTING)"), true, false);
					return true;
				}
				this.ridx += pl;
				ByteBuffer sendB = this.buildInitSend();
				if (sendB == null) {
					return true;
				}
				this.remoteConfig = remoteSettings;
				this.outputCache.offer(sendB, this);
				this.setOpWrite();
				return true;
			} else {
				this.addKeepAliveCheck();
			}
		}
		return false;
	}

	private void switchHandler() {
		assert LOG.assertDebug(this.channelId + " invoke swichHandler");
		SslHttp2ServerConnection sslChannel = new SslHttp2ServerConnection(this.executor, javaChannel, this.setting, this);
		sslChannel.setService(service);
		this.delegatedChannel = sslChannel;
		this.key.attach(sslChannel);
		sslChannel.setChannelId(this.channelId);
		sslChannel.addKeepAliveCheck();
		sslChannel.applySetting(this.remoteConfig);
		if (this.widx > this.ridx) {
			assert LOG.assertTrace(this.rbuffer, this.ridx, this.widx);
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
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " parse http2 SETTING frame error", e);
				}
				return null;
			}
		}
		return settings;
	}

	private ByteBuffer buildInitSend() {
		assert LOG.assertDebug(this.channelId + "local http2 SETTING:" + setting.toString());
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		buffer.order(ByteOrder.BIG_ENDIAN);
		// buffer.put(HttpConsts.HTTP2_PREFACE);
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
			this.setTransferException(new IllegalStateException("wrap data error"), false, false);
			return null;
		}
		buffer.clear();
		buffer.put(executor.sslByteArray, 0, executor.bytesProduced);
		buffer.flip();
		return buffer;
	}

	private static final int _ensureCapacity = ~(8192 - 1);

	protected void ensureCapacity(int size) {
		assert LOG.assertDebug(this.channelId, " invoke ensureCapacity(", size, ")");
		// int nsize =((size + 8192 - 1) & (~(8192 - 1)));
		int nsize = (size + 8191) & _ensureCapacity;
		assert LOG.assertTrace("nsize=", nsize);
		ByteBuffer bb = ByteBuffer.allocate(nsize);
		bb.order(ByteOrder.BIG_ENDIAN);
		bb.limit(size);
		this.sslReadBuffer.flip();
		bb.put(this.sslReadBuffer);
		this.sslCapacity = nsize;
		this.sslReadBuffer = bb;
	}

	private boolean readSslRecord() {
		assert LOG.assertDebug(this.channelId, " invoke readSslRecord()");
		int len = 0;
		try {
			len = this.javaChannel.read(sslReadBuffer);
		} catch (Throwable e) {
			this.setTransferException(e, true, false);
			return false;
		}
		if (sslReadBuffer.hasRemaining()) {
			if (len < 0) {
				this.setTransferException(new IllegalStateException("input close"), true, false);
			}
			// readSslHeader = true or false;
			return false;
		} else {
			if (readSslHeader) {
				len = SslUtil.getTlsPacketLength(sslReadBuffer);
				if (len > 0) {
					if (len > this.sslCapacity) {
						this.ensureCapacity(len);
					}else{
						this.sslReadBuffer.limit(len);
					}
					try {
						len = this.javaChannel.read(sslReadBuffer);
					} catch (Throwable e) {
						this.setTransferException(e, true, false);
						return false;
					}
					if (sslReadBuffer.hasRemaining()) {
						if (len < 0) {
							this.setTransferException(new IllegalStateException("input closed with readSslRecord"), true, false);
						}
						readSslHeader = false;
						// readSslHeader = false;
						return false;
					} else {
						// readSslHeader = true;
						return true;
					}
				} else {
					this.setTransferException(new IllegalAccessError("invalid TLS access"), true, false);
					return false;
				}
			} else {
				// readSslHeader = false;
				readSslHeader = true;
				return true;
			}
		}
	}

	@Override
	public void read() {
		if (this.javaChannel != null) {
			if (this.delegatedChannel != null) {
				this.delegatedChannel.read();
				return;
			}
			for (;;) {
				if (this.readSslRecord()) {
					this.sslReadBuffer.flip();
				} else {
					return;
				}
				if (unwrap()) {
					this.sslReadBuffer.clear();
					this.sslReadBuffer.limit(5);
					if (this.handleRead()) {
						return;
					}
				} else {
					return;
				}
			}
		}
	}

	private boolean checkInWrite() {
		if (this.transferException != null) {
			LOG.fatal(this.channelId + " transferException is not null", transferException);
			return false;
		}
		if (null == this.javaChannel) {
			LOG.fatal(this.channelId + " javaChannel is null");
			return false;
		}
		return true;
	}

	@Override
	public void write() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.write();
			return;
		}
		assert checkInWrite();
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
					setTransferException(e, false, false);
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

	private boolean checkInClose() {
		if (this.delegatedChannel != null) {
			LOG.fatal(this.channelId + " delegatedChannel is not null");
			return false;
		}
		if (this.javaChannel == null) {
			LOG.fatal(this.channelId + " javaChannel is null");
			return false;
		}
		return true;
	}

	@Override
	public void close() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.close();
		} else {
			assert checkInClose();
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
		assert LOG.assertDebug(this.channelId, "invoke KeepAliveTimeout()");
		this.close();
	}

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue(this.executor.getKeepAliveQueue());
			assert LOG.assertTrace(this.channelId, " invoke removeKeepAliveCheck() return true");
			return true;
		}
		assert LOG.assertTrace(this.channelId, " invoke removeKeepAliveCheck() return false");
		return false;
	}

	public boolean addKeepAliveCheck() {
		if (this.keepAliveTimeout == Long.MAX_VALUE) {
			this.keepAliveTimeout = System.currentTimeMillis();
			this.keepAliveNode.enqueue(this.executor.getKeepAliveQueue());
			assert LOG.assertTrace(this.channelId, " invoke addKeepAliveCheck() return true");
			return true;
		}
		assert LOG.assertTrace(this.channelId, " invoke addKeepAliveCheck() return false");
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

	public JdkSslEngine getWrapSslEngine() {
		return this.wrapEngine;
	}

	public TagQueue<ByteBuffer, TaskCompletionHandler> getOutCache() {
		return this.outputCache;
	}
}
