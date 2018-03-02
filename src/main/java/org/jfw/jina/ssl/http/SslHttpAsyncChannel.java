package org.jfw.jina.ssl.http;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.server.HttpChannel;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.SslUtil;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.Handler;

public class SslHttpAsyncChannel extends HttpChannel<Http2AsyncExecutor> {
	private static final Logger LOG = LogFactory.getLog(SslHttpAsyncChannel.class);
	protected ByteBuffer sslReadBuffer;
	protected int sslCapacity;
	protected int sslRidx;
	protected int sslWidx;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;

	private final ByteBuffer clearCacheBuffer = ByteBuffer.allocate(8192);
//	private final byte[] clearByteArray = clearCacheBuffer.array();

	public SslHttpAsyncChannel(Http2AsyncExecutor executor, SocketChannel javaChannel, SslAsyncChannel sslChannel) {
		super(executor, javaChannel);
		this.key = sslChannel.getSelectionKey();
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslRidx = sslChannel.getSslRidx();
		this.sslWidx = sslChannel.getSslWidx();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
		this.keepAliveNode = this.executor.newDNode(this);
		this.outputCache.free(Handler.NOOP);
		this.outputCache = sslChannel.getOutCache();
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

	private int packetLen;

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
		}
		int len = this.widx - this.ridx;
		if (len > 0)
			this.handleRead(len);
	}

	@Override
	public void read() {
		int len = 0;
		for (;;) {
			try {
				len = this.javaChannel.read(sslReadBuffer);
			} catch (Throwable e) {
				if(LOG.enableWarn()){
					LOG.warn(this.channelId+" read os buffer error",e);
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

	protected void wrapAndWriteCacheClear() {
		this.clearCacheBuffer.flip();
		for (;;) {
			int remaining = this.clearCacheBuffer.remaining();
			if (remaining > 0) {
				try {
					executor.wrap(engine, this.clearCacheBuffer);
				} catch (Throwable e) {
					if(LOG.enableWarn()){
						LOG.warn(this.channelId+" wrap data error", e);
					}
					this.writeException = e;
					this.clearCacheBuffer.clear();
					this.close();
					return;
				}
				this.clearCacheBuffer.clear();
				super.writeData(executor.sslByteArray, 0, executor.bytesProduced);
			} else {
				this.clearCacheBuffer.clear();
				return;
			}
		}
	}

	protected void flushAndWriteCacheClear(TaskCompletionHandler task) {
		this.clearCacheBuffer.flip();
		for (;;) {
			int remaining = this.clearCacheBuffer.remaining();
			if (remaining > 0) {
				try {
					executor.wrap(engine, this.clearCacheBuffer);
				} catch (Throwable e) {
					e.printStackTrace();
					this.writeException = e;
					this.clearCacheBuffer.clear();
					this.outputCache.offer(Http2AsyncExecutor.SSL_EMTPY_BUFFER, task);
					this.close();
					return;
				}
				this.clearCacheBuffer.clear();
				super.flushData(executor.sslByteArray, 0, executor.bytesProduced, task);
				return;
			} else {
				this.clearCacheBuffer.clear();
				super.flushData(task);
				return;
			}
		}
	}

	@Override
	protected void writeData(byte[] buf, int index, int len) {
		while (len > 0) {
			if (this.writeException != null) {
				break;
			}
			int remaining = this.clearCacheBuffer.remaining();
			if (remaining == 0) {
				wrapAndWriteCacheClear();
				continue;
			}
			int wl = Integer.min(len, remaining);
			this.clearCacheBuffer.put(buf, index, wl);
			index += wl;
			len -= wl;
		}
	}

	@Override
	protected void writeByteData(byte value) {
		if (this.writeException != null)
			return;
		int remaining = this.clearCacheBuffer.remaining();
		if (remaining == 0) {
			wrapAndWriteCacheClear();
			if (this.writeException == null) {
				this.clearCacheBuffer.put(value);
			}
		} else {
			this.clearCacheBuffer.put(value);
		}
	}

	@Override
	protected void flushData(byte[] buf, int index, int len, TaskCompletionHandler task) {
		assert buf != null;
		assert index >= 0;
		assert len > 0;
		assert buf.length >= (index + len);
		if (this.javaChannel != null) {
			for (;;) {
				if (this.writeException != null) {
					break;
				}
				int remaining = this.clearCacheBuffer.remaining();
				if (remaining == 0) {
					wrapAndWriteCacheClear();
					continue;
				}
				int wl = Integer.min(len, remaining);
				this.clearCacheBuffer.put(buf, index, wl);
				index += wl;
				len -= wl;
				if (len == 0) {
					flushAndWriteCacheClear(task);
					return;
				}
			}
		} else {
			task.failed(this.writeException, executor);
		}
	}

	@Override
	protected void flushData(TaskCompletionHandler task) {
		this.flushAndWriteCacheClear(task);
	}

}
