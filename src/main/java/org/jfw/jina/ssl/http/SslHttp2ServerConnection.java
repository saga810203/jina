package org.jfw.jina.ssl.http;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.impl.Http2ServerConnection;
import org.jfw.jina.http2.impl.Http2FrameWriter.Frame;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.ssl.SslUtil;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.Handler;

public class SslHttp2ServerConnection extends Http2ServerConnection<Http2AsyncExecutor> {
	private static final Logger LOG = LogFactory.getLog(SslHttp2ServerConnection.class);
	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;
	protected int sslRidx;
	protected int sslWidx;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;
	private int packetLen = 0;

	public SslHttp2ServerConnection(Http2AsyncExecutor executor, SocketChannel javaChannel, Http2Settings settings, SslHttp2CheckChannel sslChannel) {
		super(executor, javaChannel, settings);
		this.setService(service);
		this.key = sslChannel.getSelectionKey();
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslRidx = sslChannel.getSslRidx();
		this.sslWidx = sslChannel.getSslWidx();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
		this.outputCache.free(Handler.NOOP);
		this.outputCache = sslChannel.getOutCache();
		ridx = sslChannel.ridx;
		widx = sslChannel.widx;
		rbuffer = sslChannel.rbuffer;
		rBytes = sslChannel.rBytes;
		rlen = sslChannel.rlen;
	}

	@Override
	public void read() {
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
			assert LOG.debug(this.channelId + " read os buffer size is " + len);
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

	@Override
	public void close() {
		super.close();
		if (this.wrapEngine != null) {
			try {
				this.wrapEngine.closeInbound();
			} catch (SSLException e) {
			}
			this.wrapEngine.closeOutbound();

			this.wrapEngine = null;
			this.engine = null;
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
				assert LOG.debug(this.channelId + " read ssl record length:" + pl);
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
			assert LOG.debug(this.channelId + " unwrap data length:" + executor.bytesProduced);
			this.sslRidx += executor.bytesConsumed;
			int produced = executor.bytesProduced;
			assert LOG.debug(this.channelId + " unwrap data length:" + produced + " encrypt data length:" + executor.bytesConsumed);
			if (produced > 0) {
				this.ensureCapacity4ReadBuffer(produced);
				this.rbuffer.put(executor.sslByteArray, 0, produced);
				this.widx += produced;
			}
		}
		int len = this.widx - this.ridx;
		if (len > 0) {
			assert LOG.debug(this.channelId + " call handleRead(" + len + ")");
			this.handleRead(len);
		}
	}

	private boolean wrapToCache(org.jfw.jina.http2.impl.Http2FrameWriter.Frame frame) {
		ByteBuffer buffer = frame.buffer;
		int remaining = buffer.remaining();
		if (remaining > 0) {
			try {
				executor.wrap(engine, buffer);
			} catch (Throwable e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " wrap data error", e);
				}
				this.writeException = e;
				return false;
			}
			int idx = 0;
			int len = executor.bytesProduced;
			while (len > 0) {
				ByteBuffer cb = ByteBuffer.allocate(8192);
				int wl = Integer.min(8192, len);
				cb.put(executor.sslByteArray, idx, wl);
				cb.flip();
				this.outputCache.offer(cb, null);
				len -= wl;
				if (len == 0) {
					break;
				}
				idx += wl;
			}
		}else{
			this.outputCache.offer(EMPTY_BUFFER,null);
		}
		return true;
	}

	private boolean writeCacheData() throws Throwable {
		for (;;) {
			if (outputCache.isEmpty()) {
				return true;
			}
			ByteBuffer buffer = outputCache.peek();
			if (this.writeException == null) {
				if (buffer.hasRemaining()) {
					try {
						javaChannel.write(buffer);
					} catch (Throwable e) {
						this.writeException = e;
						throw e;
					}
					if (buffer.hasRemaining()) {
						return false;
					}
				}
				outputCache.unsafeShift();
			}
		}
	}

	@Override
	public void write() {
		for (;;) {
			boolean writeOver = false;
			try {
				writeOver = this.writeCacheData();
			} catch (Throwable e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + "write data error", e);
				}
				this.close();
				return;
			}
			if (writeOver) {
				Frame frame = firstFrame;
				firstFrame = frame.next;
				TaskCompletionHandler listenner = frame.listenner;
				firstFrame = frame.next;
				freeFrame(frame);
				if (listenner != null) {
					executor.safeInvokeCompleted(frame.listenner);
				}
				if (firstFrame != null) {
					if (!this.wrapToCache(firstFrame)) {
						this.close();
						return;
					}
				} else {
					this.lastFrame = null;
					this.cleanOpWrite();
					return;
				}
			} else {
				return;
			}
		}
	}



}
