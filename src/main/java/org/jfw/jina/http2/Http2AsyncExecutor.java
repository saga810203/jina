package org.jfw.jina.http2;

import java.nio.ByteBuffer;
import java.nio.channels.spi.SelectorProvider;

import org.jfw.jina.core.AsyncExecutorGroup;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.server.HttpAsyncExecutor;

public class Http2AsyncExecutor extends HttpAsyncExecutor {

	public Http2AsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, SelectorProvider selectorProvider) {
		super(group, closeTask, selectorProvider);

	}

	
	public OutputFrame outputFrame(){
		//TODO impl
		return null;
	}
	public void freeOutputFrame(OutputFrame frame){
		//TODO impl
	}
	
	
	public final DefaultHttpHeaders shareHeaders = new DefaultHttpHeaders();
	
	public final ByteBuffer deCryptBuffer = ByteBuffer.allocate(8192);
	public final byte[] deCryptByteArray = new byte[4096];
}
