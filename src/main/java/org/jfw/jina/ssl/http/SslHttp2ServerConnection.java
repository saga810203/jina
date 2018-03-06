package org.jfw.jina.ssl.http;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
	protected boolean readSslHeader;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;
	private int packetLen = 0;

	public SslHttp2ServerConnection(Http2AsyncExecutor executor, SocketChannel javaChannel, Http2Settings settings, SslHttp2CheckChannel sslChannel) {
		super(executor, javaChannel, settings);
		this.setService(service);
		this.key = sslChannel.getSelectionKey();
		this.sslReadBuffer = sslChannel.getSslReadBuffer();
		this.sslCapacity = this.sslReadBuffer.capacity();
		this.readSslHeader = true;
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
					} else {
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
		if(null!= this.cacheWriteListener){
			executor.safeInvokeFailed(this.cacheWriteListener, this.writeException);
		}
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
		this.handleRead(len);
		return this.currentState > 0;
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
		} else {
			this.outputCache.offer(EMPTY_BUFFER, null);
		}
		return true;
	}

	private TaskCompletionHandler cacheWriteListener = null;;

	private boolean writeCacheData() throws Throwable {
		assert this.writeException == null;
		for (;;) {
			if (outputCache.isEmpty()) {
				return true;
			}
			ByteBuffer buffer = outputCache.peek();
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
				if(null!= cacheWriteListener){
					executor.safeInvokeCompleted(this.cacheWriteListener);
					cacheWriteListener = null;
				}
				Frame frame = firstFrame;
				if (frame != null) {
					this.cacheWriteListener = frame.listenner;
					firstFrame = frame.next;
					if(firstFrame==null){
						this.lastFrame = null;
					}
					if (!this.wrapToCache(frame)) {
						this.close();
						return;
					}
				} else {
					this.cleanOpWrite();
					return;
				}
			} else {
				return;
			}
		}
	}

}
