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
	private HttpService service;
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
	private TagQueue<ByteBuffer, TaskCompletionHandler> outQueue;
	private Throwable transferException = null;
	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;

	public SslAsyncChannel(SslContext context, boolean isClient, Http2AsyncExecutor executor, SocketChannel javaChannel) {
		this.wrapSslEngine = context.newEngine();
		this.delegatedChannel = null;
		this.isClient = isClient;
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.sslReadBuffer = ByteBuffer.allocate(8192);
		this.sslReadBuffer.order(ByteOrder.BIG_ENDIAN);
		this.sslReadBuffer.limit(5);
		// this.sslRidx = this.sslWidx = 0;
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
			try {
				this.javaChannel.close();
			} catch (IOException e1) {
			}
			return;
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

	public String getChannelId() {
		return this.channelId;
	}

	public SelectionKey getSelectionKey() {
		return this.key;
	}

	private void setTransferException(Throwable e, boolean read, boolean ssl) {
		assert this.transferException == null;
		if (LOG.enableWarn()) {
			LOG.warn(this.channelId + " network or ssl error{read:" + read + ",ssl:" + ssl + "}:", e);
		}
		this.transferException = e;
		this.close();
	}

	private void applyHandshakeTimeout() {
		this.executor.schedule(new AsyncTask() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
			}

			@Override
			public void completed(AsyncExecutor executor) {
			}

			@Override
			public void execute(AsyncExecutor executor) throws Throwable {
				if (null == javaChannel)
					return;
				if (!handshaked) {
					setTransferException(new IllegalStateException("handshaked timeout"), false, false);
				}
			}

			@Override
			public void cancled(AsyncExecutor executor) {
				if (null == javaChannel)
					return;
				if (!handshaked) {
					setTransferException(new IllegalStateException("handshaked timeout checkTask cancled"), false, false);
				}
			}
		}, this.handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
	}

	private void handshake() {
		// TODO Auto-generated method stub

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

	private boolean readSslHeader = true;

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
				this.setTransferException(new IllegalStateException("input closed with readSslRecord"), true, false);
			}
			// readSslHeader = true or false;
			return false;
		} else {
			if (readSslHeader) {
				len = SslUtil.getTlsPacketLength(sslReadBuffer);
				if (len > 0) {
					if (len > this.sslCapacity) {
						this.ensureCapacity(len);
					} else {
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
		assert LOG.assertDebug(this.channelId, " invoke read()==>javaChannel isNull:", this.javaChannel == null);
		if (null != this.javaChannel) {
			if (this.delegatedChannel != null) {
				this.delegatedChannel.read();
				return;
			}
			for (;;) {
				SSLEngineResult.HandshakeStatus state = this.wrapSslEngine.getHandshakeStatus();
				assert LOG.assertTrace(" handshakeStatus is ", state.toString());
				if (state == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
					if (this.readSslRecord()) {
						this.sslReadBuffer.flip();
					} else {
						return;
					}
					if (unwrap()) {
						this.sslReadBuffer.clear();
						this.sslReadBuffer.limit(5);
					} else {
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
						this.setTransferException(e, false, true);
						return;
					}
				} else if (null == this.delegatedChannel) {
					this.swichHandle();
					return;
				}
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

	private static final String h2 = "h2";

	private void swichHandle() {
		assert LOG.assertDebug(this.channelId, " invoke swichHandle()");
		this.handshaked = true;
		assert LOG.assertTrace("outQueue.isEmpty:", this.outQueue.isEmpty(), "}");
		assert LOG.assertTrace("selected applicationProcotol:", this.wrapSslEngine.selectedProtocol());
		if (h2.equals(this.wrapSslEngine.selectedProtocol())) {
			SslHttp2CheckChannel shChannel = new SslHttp2CheckChannel(executor, javaChannel, this, this.localSetting);
			this.delegatedChannel = shChannel;
			this.key.attach(shChannel);
			shChannel.setChannelId(this.channelId);
			shChannel.addKeepAliveCheck();
			shChannel.setService(service);
		} else {
			SslHttpAsyncChannel shChannel = new SslHttpAsyncChannel(executor, javaChannel, this);
			this.delegatedChannel = shChannel;
			this.key.attach(shChannel);
			shChannel.setChannelId(this.channelId);
			shChannel.addKeepAliveCheck();
			shChannel.setService(this.service);
		}
	}

	private boolean unwrap() {
		try {
			executor.unwrap(wrapSslEngine, this.sslReadBuffer);
		} catch (Throwable e) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " unwrap ssl record data error", e);
			}
			this.setTransferException(e, false, true);
			return false;
		}
		if (executor.bytesProduced > 0) {
			String reason = this.channelId + " error on ssl handprotocol with appdata";
			this.setTransferException(new RuntimeException(reason), false, true);
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

			if (outQueue.isEmpty()) {
				outQueue.offer(buffer,null);
				this.setOpWrite();
			} else {
				outQueue.offer(buffer,null);
			}
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
		assert LOG.assertDebug(this.channelId, "invoke write()");
		if (this.delegatedChannel != null) {
			assert LOG.assertTrace("delegateChannel is not null");
			this.delegatedChannel.write();
			return;
		}
		assert checkInWrite();
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
		this.cleanOpWrite();
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.setSelectionKey(key);
			return;
		}

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
		assert this.executor.inLoop();
		assert checkInClose();
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
