package org.jfw.jina.core.impl;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.AsyncChannel;
import org.jfw.jina.core.AsyncExecutor;

public interface AsyncServerChannel  extends AsyncChannel{	
	void accectClient(SocketChannel channel,AsyncExecutor executor);
	void start(InetSocketAddress address,int backlog) throws  Throwable;
	void config();
}
