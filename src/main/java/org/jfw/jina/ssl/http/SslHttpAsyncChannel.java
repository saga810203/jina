package org.jfw.jina.ssl.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.server.HttpChannel;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.Queue;

public class SslHttpAsyncChannel extends HttpChannel<Http2AsyncExecutor> {

	private final byte[] byteArrayBuffer;
	private final ByteBuffer decryptBuffer;

	private ByteBuffer readBuffer;
	private int readIndex;
	private int writeIndex;
	private int capacity;

	private JdkSslEngine wrapEngine;
	private SSLEngine engine;

	public SslHttpAsyncChannel(Http2AsyncExecutor executor, SocketChannel javaChannel, SslAsyncChannel sslChannel) {
		super(executor, javaChannel);
		this.byteArrayBuffer = executor.deCryptByteArray;
		this.decryptBuffer = executor.deCryptBuffer;
		copyOutQueue(sslChannel.getOutCache());
		this.readBuffer = sslChannel.getReadBuffer();
		this.readIndex = sslChannel.getReadIndex();
		this.writeIndex = sslChannel.getWriteIndex();
		this.capacity = this.readBuffer.capacity();
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
	}

	private void compactReadBuffer() {
		if (this.readIndex > 0) {
			this.readBuffer.flip();
			this.readBuffer.position(this.readIndex);
			this.readBuffer.compact();
			this.writeIndex = this.writeIndex - this.readIndex;
			this.readIndex = 0;
			this.readBuffer.limit(this.capacity).position(writeIndex);
		}
	}

	private void extendReadBuffer() {
		this.capacity += 4096;
		ByteBuffer buffer = ByteBuffer.allocate(capacity);
		buffer.put((ByteBuffer) readBuffer.flip().position(this.readIndex));
		this.writeIndex = this.writeIndex - this.readIndex;
		this.readIndex = 0;
		this.readBuffer = buffer;
	}

	private void copyOutQueue(Queue<ByteBuffer> queue) {
		OutputBuf obuf = null;
		for (;;) {
			ByteBuffer buf = queue.poll();
			if (buf != null) {
				while (buf.hasRemaining()) {
					int len = Integer.min(buf.remaining(), byteArrayBuffer.length);
					buf.get(byteArrayBuffer, 0, len);
					obuf = this.writeBytes(obuf == null ? executor.allocBuffer() : obuf, byteArrayBuffer, 0, len);
				}
			} else {
				break;
			}
		}
		if (obuf != null && obuf.size() > 0) {
			this.outputCache.offer(obuf.input());
			obuf.release();
		}
	}

	private boolean unwrap() {
		OutputBuf buf = executor.allocBuffer();
		ByteBuffer buffer = buf.original();
		int perv = -1;
		try {
			int len = 0;
			SSLEngineResult result = null;
			SSLEngineResult.Status state = null;
			for (;;) {
				try {
					result = this.engine.unwrap((ByteBuffer) this.readBuffer.duplicate().flip().position(this.readIndex), buffer);
				} catch (SSLException e) {
					return false;
				}
				this.readIndex += result.bytesConsumed();
				len += result.bytesProduced();
				state = result.getStatus();
				switch (state) {
					case BUFFER_OVERFLOW:
						if (len > 0) {
							buf.unsafeWriteIndex(len);
							buffer.flip();
							InputBuf ib = buf.input();
							buf.release();
							buf = executor.allocBuffer();
							buffer = buf.original();
							this.handleRead(ib, len);
							len = 0;
						} else {
							buf.release();
							// TODO :
							throw new RuntimeException("ssl package length to large");
						}
						break;
					case BUFFER_UNDERFLOW:
						if (len > 0) {
							buf.unsafeWriteIndex(len);
							buffer.flip();
							InputBuf ib = buf.input();
							buf.release();
							buf = executor.allocBuffer();
							buffer = buf.original();
							this.handleRead(ib, len);
							len = 0;
							if (perv == this.readIndex) {
								return true;
							}
							perv = this.readIndex;
						} else {
							// TODO :
							if (this.readIndex == 0)
								throw new RuntimeException("ssl package length to large");
							return true;
						}
						break;
					case CLOSED:
						if (len > 0) {
							buf.unsafeWriteIndex(len);
							buffer.flip();
							InputBuf ib = buf.input();
							buf.release();
							buf = executor.allocBuffer();
							buffer = buf.original();
							this.handleRead(ib, len);
							len = 0;
							return false;
						}
						return false;
					default:
						break;
				}

			}
		} finally {
			buf.release();
			buffer = null;
		}
	}

	@Override
	public final void read() {
		int len = 0;
		for (;;) {
			this.compactReadBuffer();
			try {
				len = this.javaChannel.read(readBuffer);
			} catch (Throwable e) {
				this.close();
				return;
			}
			if (len > 0) {
				this.writeIndex += len;
				if (!this.unwrap()) {
					this.close();
					return;
				}
			} else if (len == 0) {
				if (!this.unwrap()) {
					this.close();
					return;
				}
				return;
			} else {
				if (!this.unwrap()) {
					this.close();
					return;
				}
				this.handleRead(EmptyBuf.INSTANCE, -1);
				return;
			}
		}
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
		this.
				
	}

	
}
