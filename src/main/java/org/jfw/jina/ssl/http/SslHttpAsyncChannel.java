package org.jfw.jina.ssl.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;

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
	
	private  ByteBuffer readBuffer;
	private int readIndex ;
	private int writeIndex;
	
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
		this.wrapEngine = sslChannel.getWrapSslEngine();
		this.engine = this.wrapEngine.getWrappedEngine();
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

	
	
	@Override
	public final void read() {
		int rc = 0;
		for (;;) {
			OutputBuf buf = executor.allocBuffer();
			try {
				rc = buf.writeBytes(this.javaChannel);
				if (rc > 0) {
					InputBuf ibuf = buf.input();
					this.handleRead(ibuf, rc);
					ibuf.release();
				} else if (rc < 0) {
					this.handleRead(EmptyBuf.INSTANCE, -1);
					this.cleanOpRead();
					return;
				} else {
					return;
				}
			} catch (ClosedChannelException e) {
				handleRead(EmptyBuf.INSTANCE, -1);
				this.cleanOpRead();
				return;
			} catch (IOException e) {
				handleRead(EmptyBuf.INSTANCE, -1);
				this.cleanOpRead();
				this.hanldReadException(e);
				return;
			} finally {
				buf.release();
			}
		}
	}
}
