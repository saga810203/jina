package org.jfw.jina.ssl;

import java.io.ByteArrayOutputStream;
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
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.ssl.http.SslHttpAsyncChannel;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.Queue;

public class SslAsyncChannel implements NioAsyncChannel {

	private volatile long handshakeTimeoutMillis = 10000;
	// private volatile long closeNotifyFlushTimeoutMillis = 3000;
	private boolean handshaked = false;
	private JdkSslEngine wrapSslEngine;

	public JdkSslEngine getWrapSslEngine() {
		return wrapSslEngine;
	}

	private NioAsyncChannel delegatedChannel;
	private final boolean isClient;
	private final Http2AsyncExecutor executor;
	private SocketChannel javaChannel;
	private SelectionKey key;
	private ByteArrayOutputStream cacheUnwrapData = new ByteArrayOutputStream();
	private Queue<ByteBuffer> outQueue;
	private ByteBuffer deCryptBuffer;
	// private int deRidx;
	// private int deWidx;

	public SslAsyncChannel(SslContext context, boolean isClient, Http2AsyncExecutor executor,
			SocketChannel javaChannel) {
		this.wrapSslEngine = context.newEngine();
		this.delegatedChannel = null;
		this.isClient = isClient;
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.deCryptBuffer = executor.deCryptBuffer;
		this.sslReadBuffer = ByteBuffer.allocate(8192);
		this.sslReadBuffer.order(ByteOrder.BIG_ENDIAN);
		this.sslRidx = this.sslWidx = 0;
		this.sslCapacity = sslReadBuffer.capacity();
	}

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		this.key = this.javaChannel.register(this.executor.unwrappedSelector(),
				SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
		this.afterRegister();
	}

	private void afterRegister() {
		if (this.isClient) {
			handshake();
		} else {
			applyHandshakeTimeout();
		}

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
		if(this.sslRidx== this.sslWidx){
			this.sslReadBuffer.clear();
		}else if(this.sslRidx>0 ){
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
			this.delegatedChannel.read();
			return;
		}
		int len = 0;
		for (;;) {
			try {
				len = this.javaChannel.read(sslReadBuffer);
			} catch (Throwable e) {
				this.close();
				return;
			}
			if (len == 0) {
				break;
			} else if (len < 0) {
				this.close();
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

		for (;;) {
			if (this.sslWidx - this.sslRidx > 0) {
				if (!unwrap()) {
					return;
				}
				SSLEngineResult.HandshakeStatus state = this.wrapSslEngine.getHandshakeStatus();
				switch (state) {
					case FINISHED:
						this.handshaked = true;
						break;
					case NEED_TASK:
						for (;;) {
							Runnable task = wrapSslEngine.getDelegatedTask();
							if (task == null) {
								break;
							}
							task.run();
						}
						break;
					case NEED_UNWRAP: {
						if (this.sslWidx == this.sslRidx) {
							return;
						}else{
							break;
						}
					}
					case NEED_WRAP:
						try {
							this.wrapNonAppData();
						} catch (SSLException e) {
							// TODO log and exit;
							this.close();
						}
						return;
					case NOT_HANDSHAKING:
						if (!this.handshaked) {
							this.handshaked = true;
						}
						break;
					default:
						throw new IllegalStateException("Unknown handshake status: " + state);

				}
				if (handshaked) {
					this.key.interestOps(0);
					this.swichHandle();
					return;
				}
			}
		}
	}

	public Queue<ByteBuffer> getOutCache() {
		return this.outQueue;
	}

	
	public ByteArrayOutputStream getCacheUnwrapData() {
		return cacheUnwrapData;
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

	private void swichHandle() {
		this.delegatedChannel = new SslHttpAsyncChannel(executor, javaChannel, this);
		this.key.attach(this.delegatedChannel);
	}

	private int packetLen = 0;

	private boolean unwrap() {
		int sslReadableBytes = this.sslWidx - this.sslRidx;

		if (packetLen > 0) {
			if (sslReadableBytes < packetLen) {
				return false;
			}
		} else {
			if (sslReadableBytes < SslUtil.SSL_RECORD_HEADER_LENGTH) {
				return false;
			}
			int pl = SslUtil.getEncryptedPacketLength(this.sslReadBuffer, this.sslRidx);
			if (pl == SslUtil.NOT_ENCRYPTED) {
				// Not an SSL/TLS packet
				// TODO : log "not an SSL/TLS record: " +
				// ByteBufUtil.hexDump(in));
				this.close();
				return false;
			} else if (sslReadableBytes < pl) {
				this.packetLen = pl;
				return false;
			}
		}

		ByteBuffer buf = (ByteBuffer) this.sslReadBuffer.duplicate().flip().position(this.sslRidx)
				.limit(this.sslRidx + this.packetLen);
		deCryptBuffer.clear();
		SSLEngineResult result;
		try {
			result = this.wrapSslEngine.unwrap(buf, deCryptBuffer);
		} catch (SSLException e) {
			this.close();
			return false;
		}
		int produced = result.bytesProduced();
		int consumed = result.bytesConsumed();
		this.sslRidx += consumed;

		switch (result.getStatus()) {
			case BUFFER_OVERFLOW:
				// TODO log and gu exit IGNORE not happend
				throw new RuntimeException("decrypt buffer too small");
			case CLOSED:
				this.close();
				return false;
			default:
				break;
		}
		if (produced > 0) {
			this.cacheUnwrapData(deCryptBuffer, produced);
		}
		return true;
	}

	public static ByteBuffer SSL_EMTPY_BUFFER = ByteBuffer.allocate(0);

	private void wrapNonAppData() throws SSLException {
		ByteBuffer buffer = null;
		int packLength = 2048;
		for (;;) {
			if (buffer == null) {
				buffer = ByteBuffer.allocate(2048);
				packLength = 2048;
			}
			SSLEngineResult result = this.wrapSslEngine.wrap(SSL_EMTPY_BUFFER, buffer);
			if (result.bytesProduced() > 0) {
				buffer.flip();
				this.outQueue.offer(buffer);
				buffer = null;
			}
			if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				packLength += 2048;
				buffer = ByteBuffer.allocate(packLength);
				continue;
			}
			if (result.bytesProduced() == 0) {
				return;
			}
		}
	}

	private void cacheUnwrapData(ByteBuffer buffer, int len) {
		byte[] bs = new byte[4096];
		buffer.flip();
		while (len > 0) {
			int nl = Integer.min(len, 4096);
			buffer.get(bs, 0, nl);
			this.cacheUnwrapData.write(bs, 0, nl);
			len -= nl;
		}
		buffer.clear();
	}

	@Override
	public void write() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.write();
			return;
		}
		ByteBuffer buffer = null;
		while ((buffer = this.outQueue.peek()) != null) {
			try {
				this.javaChannel.write(buffer);
			} catch (Throwable e) {
				this.close();
			}
			if (buffer.hasRemaining()) {
				return;
			}
			this.outQueue.unsafeShift();
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
			this.delegatedChannel.close();
			return;
		}
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
