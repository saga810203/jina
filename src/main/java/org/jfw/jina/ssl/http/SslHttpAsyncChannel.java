package org.jfw.jina.ssl.http;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
	protected boolean readSslHeader;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;

	private final ByteBuffer clearCacheBuffer = ByteBuffer.allocate(8192);
	// private final byte[] clearByteArray = clearCacheBuffer.array();

	public SslHttpAsyncChannel(Http2AsyncExecutor executor, SocketChannel javaChannel, SslAsyncChannel sslChannel) {
		super(executor, javaChannel);
		this.key = sslChannel.getSelectionKey();
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.readSslHeader = true;
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
		this.keepAliveNode = this.executor.newDNode(this);
		this.outputCache.free(Handler.NOOP);
		this.outputCache = sslChannel.getOutCache();
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
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + "javaChannel.read error", e);
			}
			this.removeKeepAliveCheck();
			this.cleanOpRead();
			this.handleInputClose();
			return false;
		}
		if (sslReadBuffer.hasRemaining()) {
			if (len < 0) {
				this.removeKeepAliveCheck();
				this.cleanOpRead();
				this.handleInputClose();
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
						if (LOG.enableWarn()) {
							LOG.warn(this.channelId + "javaChannel.read error", e);
						}
						this.removeKeepAliveCheck();
						this.cleanOpRead();
						this.handleInputClose();
						return false;
					}
					if (sslReadBuffer.hasRemaining()) {
						if (len < 0) {
							this.removeKeepAliveCheck();
							this.cleanOpRead();
							this.handleInputClose();
						}
						readSslHeader = false;
						// readSslHeader = false;
						return false;
					} else {
						// readSslHeader = true;
						return true;
					}
				} else {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + "read ssl record length error:" + len);
					}
					this.removeKeepAliveCheck();
					this.cleanOpRead();
					this.handleInputClose();
					return false;
				}
			} else {
				// readSslHeader = false;
				readSslHeader = true;
				return true;
			}
		}
	}

	private boolean unwrap() {

		try {
			this.executor.unwrap(this.engine, this.sslReadBuffer);
		} catch (Exception e) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " unwrap error", e);
			}
			this.cleanOpRead();
			this.removeKeepAliveCheck();
			this.handleInputClose();
			return false;
		}
		int produced = executor.bytesProduced;
		assert produced > 0;
		// if (produced > 0) {
		this.ensureCapacity4ReadBuffer(produced);
		this.rbuffer.put(executor.sslByteArray, 0, produced);
		this.widx += produced;
		// }

		int len = this.widx - this.ridx;
		return this.handleRead(len);
	}

	@Override
	public void read() {
		int len = 0;
		if (this.javaChannel != null) {
			for (;;) {
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
			}
		}
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
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " wrap data error", e);
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
