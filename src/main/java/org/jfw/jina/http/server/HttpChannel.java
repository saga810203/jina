package org.jfw.jina.http.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.util.concurrent.AsyncChannel;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.Node;

public class HttpChannel implements AsyncChannel{
	
	private SocketChannel javaChannel;
	private SelectionKey key;
	private HttpAsyncExecutor executor;
	
	private Node keepAliveNode;
	
	
	public HttpChannel(AsyncExecutor executor,SocketChannel javaChannel ) {
		this.executor=(HttpAsyncExecutor)executor;
		this.javaChannel = javaChannel;
		this.keepAliveNode = this.executor.getNode();
		this.keepAliveNode.next = null;
		this.keepAliveNode.prev = null;
		this.keepAliveNode.item = this;
		this.keepAliveNode.time = Long.MAX_VALUE;
	}

	public Node getKeepAliveNode() {
		return keepAliveNode;
	}
	
	public void removeKeepAliveCheck(){
		Node node = this.keepAliveNode.next;
		node.prev = this.keepAliveNode.prev;
		node.prev = node;
	}

	@Override
	public void read() {
		this.removeKeepAliveCheck();		
		try{
			
		}finally{
			this.executor.checkKeepAlive(this);
		}
	}

	@Override
	public void write() {
		try{
			
		}finally{
			this.executor.checkKeepAlive(this);
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

}
