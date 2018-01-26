package org.jfw.jina.core.impl;

import java.nio.channels.spi.SelectorProvider;

import org.jfw.jina.core.AsyncExecutor;

public class NioAsyncExecutorGroup extends AbstractAsyncExecutorGroup {
	 private static final int DEFAULT_EVENT_LOOP_THREADS;
	    static {
	        DEFAULT_EVENT_LOOP_THREADS = Math.max(1,Runtime.getRuntime().availableProcessors());
	    }
	public NioAsyncExecutorGroup(int nThreads) {
		super(nThreads<=0?DEFAULT_EVENT_LOOP_THREADS:nThreads);
	}
	public NioAsyncExecutorGroup() {
		super(DEFAULT_EVENT_LOOP_THREADS);
	}

	@Override
	public AsyncExecutor newChild(Runnable closeTask) {
		 return new NioAsyncExecutor(this, closeTask, SelectorProvider.provider());
	}
	@Override
	public Object getParameter(Object key) {
		// TODO Auto-generated method stub
		return null;
	}

}
