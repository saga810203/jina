package org.jfw.jina.demo.http;

import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashMap;
import java.util.Map;

import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.util.concurrent.AbstractAsyncServerChannel;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutorGroup;

public class HttpMain {

	
	public static void main(String[] args) throws Exception{
		NioAsyncExecutorGroup boss = new NioAsyncExecutorGroup(1);
		NioAsyncExecutorGroup worker = new NioAsyncExecutorGroup(){

			@Override
			protected Map<Object, Object> buildResources() {
			     HashMap<Object,Object> ret = new HashMap<Object,Object>();
			     return ret;
			}
			protected AsyncExecutor newChild(Runnable closeTask) {
				return new HttpAsyncExecutor(this, closeTask, SelectorProvider.provider(), this.buildResources());
			}
		};

		
		AbstractAsyncServerChannel server = new AbstractAsyncServerChannel(boss, worker){

			@Override
			public void accectClient(SocketChannel channel, AsyncExecutor executor) {
				
				
			}
			
		};
		
		
	}
	
	
}
