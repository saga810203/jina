package org.jfw.jina.http2;

import java.nio.channels.spi.SelectorProvider;

import org.jfw.jina.core.AsyncExecutorGroup;
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
}