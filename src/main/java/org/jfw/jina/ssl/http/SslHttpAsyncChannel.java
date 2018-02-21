package org.jfw.jina.ssl.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

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
					this.writeData(byteArrayBuffer, 0, len);
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
				result = this.engine.unwrap((ByteBuffer) this.sslReadBuffer.duplicate().flip().position(this.sslRidx),
						this.rbuffer);
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
		if(this.wrapEngine!=null)
			try {
				this.wrapEngine.closeInbound();
			} catch (SSLException e) {
			}
		this.wrapEngine.closeOutbound();
		this.wrapEngine = null;
		this.engine = null;		
	}

}
