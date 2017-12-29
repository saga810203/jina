package org.jfw.jina.http.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncChannel;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor;

public class HttpChannel implements AsyncChannel{
	
	private SocketChannel javaChannel;
	private SelectionKey key;
	private HttpAsyncExecutor executor;
	
	private Node keepAliveNode;
	private final Queue keepAliveQueue;
	private final long keepAliveTimeout;
	
	
	public HttpChannel(AsyncExecutor executor,SocketChannel javaChannel ) {
		this.executor=(HttpAsyncExecutor)executor;
		this.javaChannel = javaChannel;
		this.keepAliveQueue = this.executor.getKeepAliveQueue();
		this.keepAliveTimeout = this.executor.getKeepAliveTimeout();
	}

	
	public void removeKeepAliveCheck(){
		assert null != this.keepAliveNode;
		this.keepAliveQueue.remove(this.keepAliveNode);
	}

	@Override
	public void read() {
		this.removeKeepAliveCheck();		
		try{
			
		}finally{
			this.keepAliveNode = this.keepAliveQueue.add(System.currentTimeMillis() + this.keepAliveTimeout);
		}
	}

	@Override
	public void write() {
		try{

		}finally{
			this.keepAliveNode = this.keepAliveQueue.add(System.currentTimeMillis() + this.keepAliveTimeout);
		}
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		
	}
	@Override
	public void close() {
		
		
	}

	@Override
	public void connected() {
		// TODO Auto-generated method stub
		
	}
	
	public void keepAliveTimeout(){
		
	}

}
