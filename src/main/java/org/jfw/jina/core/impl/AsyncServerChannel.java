package org.jfw.jina.core.impl;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.AsyncExecutor;

public interface AsyncServerChannel  extends NioAsyncChannel{	
	void accectClient(SocketChannel channel,AsyncExecutor executor);
	void start(InetSocketAddress address,int backlog) throws  Throwable;
	void config();
}
