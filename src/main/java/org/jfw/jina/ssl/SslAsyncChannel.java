package org.jfw.jina.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.core.impl.AbstractNioAsyncChannel;
import org.jfw.jina.http.server.HttpService;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.ssl.http.SslHttp2CheckChannel;
import org.jfw.jina.ssl.http.SslHttpAsyncChannel;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.TagQueue;

public class SslAsyncChannel implements NioAsyncChannel {
	private static final Logger LOG = LogFactory.getLog(SslAsyncChannel.class);
	private String channelId;
	private volatile long handshakeTimeoutMillis = 10000;
	// private volatile long closeNotifyFlushTimeoutMillis = 3000;
	private boolean handshaked = false;
	private JdkSslEngine wrapSslEngine;
	private HttpService service ;
	private Http2Settings localSetting;
	
	public Http2Settings getLocalSetting() {
		return localSetting;
	}

	public void setLocalSetting(Http2Settings localSetting) {
		this.localSetting = localSetting;
	}

	public HttpService getService() {
		return service;
	}

	public void setService(HttpService service) {
		this.service = service;
	}

	public JdkSslEngine getWrapSslEngine() {
		return wrapSslEngine;
	}

	private NioAsyncChannel delegatedChannel;
	private final boolean isClient;
	private final Http2AsyncExecutor executor;
	private SocketChannel javaChannel;
	private SelectionKey key;
	// private ByteArrayOutputStream cacheUnwrapData = new
	// ByteArrayOutputStream();
	private TagQueue<ByteBuffer, TaskCompletionHandler> outQueue;
	private Throwable writeException = null;

	public SslAsyncChannel(SslContext context, boolean isClient, Http2AsyncExecutor executor, SocketChannel javaChannel) {
		this.wrapSslEngine = context.newEngine();
		this.delegatedChannel = null;
		this.isClient = isClient;
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.sslReadBuffer = ByteBuffer.allocate(8192);
		this.sslReadBuffer.order(ByteOrder.BIG_ENDIAN);
		this.sslRidx = this.sslWidx = 0;
		this.sslCapacity = sslReadBuffer.capacity();
		this.outQueue = executor.<ByteBuffer, TaskCompletionHandler> newTagQueue();
		if (!isClient) {
			try {
				this.wrapSslEngine.unwrap(AbstractNioAsyncChannel.EMPTY_BUFFER, AbstractNioAsyncChannel.EMPTY_BUFFER);
			} catch (SSLException e) {
			}
		}
	}

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		try {
			this.javaChannel.configureBlocking(false);
		} catch (IOException e) {
			LOG.error(channelId + " configBlocking(false )  error", e);
		}
		this.key = this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
		this.afterRegister();
	}

	private void afterRegister() {
		if (this.isClient) {
			handshake();
		} else {
			applyHandshakeTimeout();
		}
	}

	@Override
	public void setChannelId(String id) {
		this.channelId = id;
	}
	public String getChannelId(){
		return this.channelId;
	}
	public SelectionKey getSelectionKey() {
		return this.key;
	}

	private void applyHandshakeTimeout() {
		this.executor.schedule(new AsyncTask() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
			}

			@Override
			public void completed(AsyncExecutor executor) {
				// TODO Auto-generated method stub

			}

			@Override
			public void execute(AsyncExecutor executor) throws Throwable {
				if (handshaked) {
					return;
				} else {
					close();
				}
			}

			@Override
			public void cancled(AsyncExecutor executor) {
				close();
			}
		}, this.handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
	}

	private void handshake() {
		// TODO Auto-generated method stub

	}

	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;
	protected int sslRidx;
	protected int sslWidx;

	private void extendSslReadBuffer() {
		this.sslCapacity += 4096;
		ByteBuffer buffer = ByteBuffer.allocate(sslCapacity);
		buffer.put((ByteBuffer) sslReadBuffer.flip().position(this.sslRidx));
		this.sslWidx = this.sslWidx - this.sslRidx;
		this.sslRidx = 0;
		this.sslReadBuffer = buffer;
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

	@Override
	public void read() {
		if (this.delegatedChannel != null) {
			assert LOG.debug(this.channelId + " delegateChannel is not null");
			this.delegatedChannel.read();
			return;
		}
		int len = 0;
		for (;;) {
			try {
				len = this.javaChannel.read(sslReadBuffer);
			} catch (Throwable e) {
				if (LOG.enableWarn()) {
					LOG.warn(channelId + " read from os buffer error", e);
				}
				this.close();
				return;
			}
			assert LOG.debug(channelId + " read from os buffer bytes size is " + len);
			if (len == 0) {
				if (this.sslWidx >= this.sslCapacity) {
					this.compactSslReadBuffer();
				}
			} else if (len < 0) {
				this.close();
				return;
			} else {
				this.sslWidx += len;
				if (this.sslWidx >= sslCapacity) {
					if (this.sslRidx > 0) {
						this.compactSslReadBuffer();
					} else {
						this.extendSslReadBuffer();
					}
				} else {
					break;
				}
			}
		}

		for (;;) {
			if (this.javaChannel == null) {
				assert LOG.debug(this.channelId + " javaChannel is null");
				return;
			}
			SSLEngineResult.HandshakeStatus state = this.wrapSslEngine.getHandshakeStatus();
			assert LOG.debug(this.channelId + " handshakeStatus is " + state.toString());
			if (state == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
				if (this.sslWidx - this.sslRidx > 0) {
					if (!unwrap()) {
						assert LOG.debug(this.channelId + " unwrap return false");
						return;
					}
				} else {
					assert LOG.debug(this.channelId + " unwrap return false");
					return;
				}
			} else if (state == SSLEngineResult.HandshakeStatus.NEED_TASK) {
				for (;;) {
					Runnable task = wrapSslEngine.getDelegatedTask();
					if (task == null) {
						break;
					}
					task.run();
				}
			} else if (state == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
				try {
					executor.wrapHandData(this.wrapSslEngine);
					if (executor.bytesProduced > 0) {
						this.flushData();
					}
				} catch (Exception e) {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " wrap and send handshake data error", e);
					}
					this.close();
					return;
				}
			} else if (null == this.delegatedChannel) {
				this.swichHandle();
				return;
			}
		}
	}

	public TagQueue<ByteBuffer, TaskCompletionHandler> getOutCache() {
		return this.outQueue;
	}

	public ByteBuffer getSslReadBuffer() {
		return this.sslReadBuffer;
	}

	public int getSslCapacity() {
		return sslCapacity;
	}

	public void setSslCapacity(int sslCapacity) {
		this.sslCapacity = sslCapacity;
	}

	public int getSslRidx() {
		return sslRidx;
	}

	public void setSslRidx(int sslRidx) {
		this.sslRidx = sslRidx;
	}

	public int getSslWidx() {
		return sslWidx;
	}

	public void setSslWidx(int sslWidx) {
		this.sslWidx = sslWidx;
	}

	private static final String h2 = "h2";

	private void swichHandle() {
		this.handshaked = true;
		assert LOG.debug(this.channelId + " begin swichHandle {sslRidx:" + this.sslRidx + ",sslWidx:" + this.sslWidx + ",sslCapacity:" + this.sslCapacity
				+ ",outQueue.isEmpty:" + this.outQueue.isEmpty() + "}");
		assert LOG.debug(this.channelId + " selected applicationProcotol:" + this.wrapSslEngine.selectedProtocol());
		if (h2.equals(this.wrapSslEngine.selectedProtocol())) {
			SslHttp2CheckChannel shChannel = new SslHttp2CheckChannel(executor, javaChannel, this, this.localSetting);
			this.delegatedChannel = shChannel;
			this.key.attach(shChannel);
			shChannel.setChannelId(this.channelId);
			shChannel.addKeepAliveCheck();
			shChannel.setService(service);
			if (this.sslWidx - this.sslRidx > 0) {
				shChannel.read();
			}			
			
		} else {
			SslHttpAsyncChannel shChannel = new SslHttpAsyncChannel(executor, javaChannel, this);
			this.delegatedChannel = shChannel;
			this.key.attach(shChannel);
			shChannel.setChannelId(this.channelId);
			shChannel.addKeepAliveCheck();
			shChannel.setService(this.service);
			if (this.sslWidx - this.sslRidx > 0) {
				shChannel.read();
			}
		}
	}

	private int packetLen = 0;

	private boolean unwrap() {
		int sslReadableBytes = this.sslWidx - this.sslRidx;
		assert LOG.debug(this.channelId + " packetLen == " + this.packetLen + " sslReadableBytes == " + sslReadableBytes);
		if (packetLen > 0) {
			if (sslReadableBytes < packetLen) {
				return false;
			}
		} else {
			if (sslReadableBytes < SslUtil.SSL_RECORD_HEADER_LENGTH) {
				return false;
			}
			int pl = SslUtil.getEncryptedPacketLength(this.sslReadBuffer, this.sslRidx);
			assert LOG.debug(this.channelId + " next pack length is " + pl);
			if (pl == SslUtil.NOT_ENCRYPTED) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " invalid ssl record");
				}
				this.close();
				return false;
			} else {
				assert LOG.debug(this.channelId + " next next ssl record length is " + pl);
				this.packetLen = pl;
				if (sslReadableBytes < pl) {
					return false;
				}
			}
		}
		try {
			executor.unwrap(wrapSslEngine, this.sslReadBuffer, this.sslRidx, this.packetLen);
		} catch (Throwable e) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " unwrap ssl record data error", e);
			}
			if (this.writeException == null) {
				this.writeException = e;
			}
			this.close();
			return false;
		}
		this.packetLen = 0;
		this.sslRidx += executor.bytesConsumed;
		if (executor.bytesProduced > 0) {
			String reason = this.channelId + " error on ssl handprotocol with appdata";
			LOG.error(reason);
			if (this.writeException == null) {
				this.writeException = new RuntimeException(reason);
			}
			this.close();
			return false;
		}
		return true;
	}

	public void flushData() {
		int idx = 0;
		int len = executor.bytesProduced;
		while (len > 0) {
			int wl = len > 8192 ? 8192 : len;
			ByteBuffer buffer = ByteBuffer.allocate(8192);
			buffer.put(executor.sslByteArray, idx, wl);
			idx += wl;
			len -= wl;
			buffer.flip();
			this.write(buffer);
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

	protected void write(ByteBuffer buffer) {
		if (this.writeException == null) {
			if (outQueue.isEmpty()) {
				if (buffer.hasRemaining()) {
					try {
						this.javaChannel.write(buffer);
					} catch (IOException e) {
						if (LOG.enableWarn()) {
							LOG.warn(this.channelId + " channel write error", e);
						}
						this.writeException = e;
						this.close();
						return;
					}
					if (buffer.hasRemaining()) {
						assert LOG.debug(this.channelId + " write data no over");
						outQueue.offer(buffer);
						this.setOpWrite();
					}
				}
			} else {
				outQueue.offer(buffer);
			}
		}
	}

	@Override
	public void write() {
		if (this.delegatedChannel != null) {
			assert LOG.debug(this.channelId + " delegateChannel is not null");
			this.delegatedChannel.write();
			return;
		}
		if (this.writeException != null) {
			assert LOG.debug(this.channelId + " writeException is not null", this.writeException);
			return;
		}
		ByteBuffer buffer = null;
		while ((buffer = this.outQueue.peek()) != null) {
			try {
				this.javaChannel.write(buffer);
			} catch (Throwable e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " channel write error", e);
				}
				this.close();
				return;
			}
			if (buffer.hasRemaining()) {
				return;
			}
			this.outQueue.unsafeShift();
		}
		if (this.key != null) {
			this.cleanOpWrite();
		}
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.setSelectionKey(key);
			return;
		}

	}

	@Override
	public void close() {
		assert this.executor.inLoop();
		if (this.delegatedChannel != null) {
			assert LOG.debug(this.channelId + " delegateChannel is not null");
			return;
		}
		assert LOG.debug(this.channelId + " invoke close()",
				this.writeException == null ? new RuntimeException("channel writeException is null") : this.writeException);
		SocketChannel jc = this.javaChannel;
		SelectionKey k = this.key;
		this.javaChannel = null;
		this.key = null;
		if (k != null) {
			try {
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
		if (this.wrapSslEngine != null) {
			try {
				this.wrapSslEngine.closeInbound();
			} catch (Throwable e) {

			}
			try {
				this.wrapSslEngine.closeOutbound();
			} catch (Throwable e) {
			}
			this.wrapSslEngine = null;

		}
		this.sslReadBuffer = null;
		if (this.outQueue != null) {
			this.outQueue.free(Handler.NOOP);
			this.outQueue = null;
		}

	}

	@Override
	public void connected() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.connected();
			return;
		}

	}
}
