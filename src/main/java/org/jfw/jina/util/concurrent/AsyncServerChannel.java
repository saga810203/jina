package org.jfw.jina.util.concurrent;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public interface AsyncServerChannel  extends AsyncChannel{	
	void accectClient(SocketChannel channel,AsyncExecutor executor);
	void start(InetSocketAddress address,int backlog) throws  Throwable;
	void config();
}
