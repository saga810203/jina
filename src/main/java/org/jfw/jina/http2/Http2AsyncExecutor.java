package org.jfw.jina.http2;

import java.nio.ByteBuffer;
import java.nio.channels.spi.SelectorProvider;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import org.jfw.jina.core.AsyncExecutorGroup;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;

public class Http2AsyncExecutor extends HttpAsyncExecutor {
	@SuppressWarnings("unused")
	private static final Logger LOG = LogFactory.getLog(Http2AsyncExecutor.class);
	
	public static final int DEFAULT_SSL_BUFFER_SIZE = 1024 * 128;

	public Http2AsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, SelectorProvider selectorProvider) {
		super(group, closeTask, selectorProvider);
	}

//	public OutputFrame outputFrame() {
//		return null;
//	}
//
//	public void freeOutputFrame(OutputFrame frame) {
//	}

//	public final DefaultHttpHeaders shareHeaders = new DefaultHttpHeaders();
//

	public final ByteBuffer sslBuffer = ByteBuffer.allocate(DEFAULT_SSL_BUFFER_SIZE);
	public final byte[] sslByteArray = sslBuffer.array();

	public final ByteBuffer wrapBuffer = ByteBuffer.allocate(8192);

	public int bytesProduced;
	public int bytesConsumed;

	public void wrap(SSLEngine engine, byte[] data, int index, int length) throws SSLException {
		wrapBuffer.clear();
		if (length > 0) {
			wrapBuffer.put(data, index, length);
			wrapBuffer.flip();
		}
		sslBuffer.clear();
		SSLEngineResult result = engine.wrap(wrapBuffer, sslBuffer);
		bytesProduced = result.bytesProduced();
		bytesConsumed = result.bytesConsumed();
		SSLEngineResult.Status state = result.getStatus();
		if (state == SSLEngineResult.Status.BUFFER_OVERFLOW) {
			throw new IllegalArgumentException("to large encrypt data nosupported");
		} else if (state == SSLEngineResult.Status.CLOSED) {
			throw new IllegalStateException("sslEngine is closed");
		}
	}
	public void wrap(SSLEngine engine, ByteBuffer buffer) throws SSLException {
		sslBuffer.clear();
		SSLEngineResult result = engine.wrap(buffer, sslBuffer);
		bytesProduced = result.bytesProduced();
		bytesConsumed = result.bytesConsumed();
		SSLEngineResult.Status state = result.getStatus();
		if (state == SSLEngineResult.Status.BUFFER_OVERFLOW) {
			throw new IllegalArgumentException("to large encrypt data nosupported");
		} else if (state == SSLEngineResult.Status.CLOSED) {
			throw new IllegalStateException("sslEngine is closed");
		}
	}
	
	public static ByteBuffer SSL_EMTPY_BUFFER = ByteBuffer.allocate(0);
	public void wrapHandData(SSLEngine engine)throws SSLException{
		sslBuffer.clear();
		SSLEngineResult result = engine.wrap(SSL_EMTPY_BUFFER, sslBuffer);
		bytesProduced = result.bytesProduced();
		SSLEngineResult.Status state = result.getStatus();
		if (state == SSLEngineResult.Status.BUFFER_OVERFLOW) {
			throw new IllegalArgumentException("to large encrypt data nosupported");
		} else if (state == SSLEngineResult.Status.CLOSED) {
			throw new IllegalStateException("sslEngine is closed");
		}
	}
	
	public void unwrap(SSLEngine engine,ByteBuffer sbuffer,int ridx,int length)throws SSLException{
		ByteBuffer buffer = sbuffer.duplicate();
		buffer.limit(ridx+ length);
		buffer.position(ridx);
		sslBuffer.clear();
		SSLEngineResult result = engine.unwrap(buffer, sslBuffer);
		bytesProduced = result.bytesProduced();
		bytesConsumed = result.bytesConsumed();
		SSLEngineResult.Status state = result.getStatus();
		if (state == SSLEngineResult.Status.BUFFER_OVERFLOW) {
			throw new IllegalArgumentException("too large encrypt data nosupported");
		} else if (state == SSLEngineResult.Status.CLOSED) {
			throw new IllegalStateException("sslEngine is closed");
		}else if(state != SSLEngineResult.Status.OK){
			throw new IllegalStateException("error package length with SslUtil.getEncryptedPacketLength");
		}
	}
}
