package org.jfw.jina.ssl.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.server.HttpChannel;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.SslUtil;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.Queue;

public class SslHttpAsyncChannel extends HttpChannel<Http2AsyncExecutor> {

	private final byte[] byteArrayBuffer;
	private final ByteBuffer decryptBuffer;

	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;
	protected int sslRidx;
	protected int sslWidx;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;

	public SslHttpAsyncChannel(Http2AsyncExecutor executor, SocketChannel javaChannel, SslAsyncChannel sslChannel) {
		super(executor, javaChannel);
		this.byteArrayBuffer = executor.deCryptByteArray;
		this.decryptBuffer = executor.deCryptBuffer;
		copyOutQueue(sslChannel.getOutCache());
		copyInQueue(sslChannel.getCacheUnwrapData());
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslRidx = sslChannel.getSslRidx();
		this.sslWidx = sslChannel.getSslWidx();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
	}

	private void copyInQueue(ByteArrayOutputStream buffer) {
		int len = buffer.size();
		byte[] bds = buffer.toByteArray();
		int bidx = 0;
		while (len > 0) {
			int dl = Integer.min(len, rbuffer.capacity());
			rbuffer.clear();
			rbuffer.put(bds, bidx, dl);
			this.ridx = 0;
			this.widx = dl;
			this.handleRead(dl);
			bidx += dl;
			len -= dl;
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

	private void copyOutQueue(Queue<ByteBuffer> queue) {
		for (;;) {
			ByteBuffer buf = queue.poll();
			if (buf != null) {
				while (buf.hasRemaining()) {
					int len = Integer.min(buf.remaining(), byteArrayBuffer.length);
					buf.get(byteArrayBuffer, 0, len);
					super.writeData(byteArrayBuffer, 0, len);
				}
			} else {
				break;
			}
		}
	}

	private int packetLen;

	private void unwrap() {
		SSLEngineResult result = null;
		SSLEngineResult.Status state = null;
		for (;;) {
			int dl = this.sslWidx - this.sslRidx;
			if (this.packetLen > 0) {
				if (dl < this.packetLen) {
					break;
				}
			} else {
				if (dl < SslUtil.SSL_RECORD_HEADER_LENGTH) {
					return;
				}
				int pl = SslUtil.getEncryptedPacketLength(this.sslReadBuffer, this.sslRidx);
				if (pl == SslUtil.NOT_ENCRYPTED) {
					// Not an SSL/TLS packet
					// TODO : log "not an SSL/TLS record: " +
					// ByteBufUtil.hexDump(in));
					this.close();
					return;
				} else if (dl < pl) {
					this.packetLen = pl;
					return;
				}
			}
			try {
				result = this.engine.unwrap((ByteBuffer) this.sslReadBuffer.duplicate().flip().position(this.sslRidx), this.rbuffer);
			} catch (SSLException e) {
				this.close();
				return;
			}
			this.sslRidx += result.bytesConsumed();
			this.widx += result.bytesProduced();
			int len = this.widx - this.ridx;
			state = result.getStatus();
			switch (state) {
				case BUFFER_OVERFLOW: {
					if (len > 0) {
						this.handleRead(len);
						this.compactReadBuffer();
					} else {
						this.close();
						// TODO :
						throw new RuntimeException("ssl package length to large");
					}
					break;
				}
				case BUFFER_UNDERFLOW: {
					if (this.sslRidx > 0) {
						this.compactSslReadBuffer();
					} else {
						this.close();
						throw new RuntimeException("ssl package length to large");
					}
					break;
				}
				case CLOSED:
					if (len > 0) {
						this.handleRead(len);
						this.handleInputClose();
						return;
					}
					return;
				default:
					break;
			}

		}

	}

	@Override
	public void read() {
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
				unwrap();
				this.handleInputClose();
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

	@Override
	public void close() {
		super.close();
		if (this.wrapEngine != null)
			try {
				this.wrapEngine.closeInbound();
			} catch (SSLException e) {
			}
		this.wrapEngine.closeOutbound();
		this.wrapEngine = null;
		this.engine = null;
	}

	private final ByteBuffer[] bufferArray = new ByteBuffer[3];

	protected static int wrap(SSLEngine sslEngine, ByteBuffer buffer, ByteBuffer[] dest) throws SSLException {
		for (int i = 0; i < 3; i++) {
			if (dest[i] == null) {
				dest[i] = ByteBuffer.allocate(8192);
				dest[i].order(ByteOrder.BIG_ENDIAN);
				dest[i].clear();
			} else {
				break;
			}
		}
		int idx = 0;
		for (;;) {
			if (idx > 3) {
				throw new RuntimeException("too large byteBuffer with wrap");
			}
			SSLEngineResult result = sslEngine.wrap(buffer, dest[idx]);
			SSLEngineResult.Status state = result.getStatus();
			if (state == SSLEngineResult.Status.OK) {
				if (!buffer.hasRemaining()) {
					dest[idx].flip();
					return idx;
				}
			} else if (state == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				dest[idx].flip();
				++idx;
			} else if (state == SSLEngineResult.Status.CLOSED) {
				return -1;
			}
		}
	}

	private static final Exception CLOSED_SSLENGINE_EXC = new Exception("SSLEngine is closed");

	@Override
	protected void write(ByteBuffer buffer) {
		if (this.writeException == null) {
			int ret = 0;
			try {
				ret = wrap(engine, buffer, bufferArray);
			} catch (SSLException e) {
				this.writeException = e;
				this.close();
				return;
			}
			if (ret < 0) {
				this.writeException = CLOSED_SSLENGINE_EXC;
				this.close();
				return;
			}
			for (int i = 0; i <= ret; ++i) {
				super.write(bufferArray[i]);
				bufferArray[i] = null;
			}
		}
	}

	@Override
	protected void write(ByteBuffer buffer, TaskCompletionHandler task) {
		if (this.writeException == null) {
			if (buffer.hasRemaining()) {

				int ret = 0;
				try {
					ret = wrap(engine, buffer, bufferArray);
				} catch (SSLException e) {
					this.writeException = e;
					this.close();
					return;
				}
				if (ret < 0) {
					this.writeException = CLOSED_SSLENGINE_EXC;
					this.close();
					return;
				}
				for (int i = 0; i < ret; ++i) {
					super.write(bufferArray[i]);
					bufferArray[i] = null;
				}
				super.write(bufferArray[ret], task);
				bufferArray[ret] = null;
			} else {
				super.write(buffer, task);
			}
		} else {
			executor.safeInvokeFailed(task, this.writeException);
		}
	}

}
