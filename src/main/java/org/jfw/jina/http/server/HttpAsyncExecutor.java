package org.jfw.jina.http.server;

import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncExecutorGroup;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public class HttpAsyncExecutor extends NioAsyncExecutor {
	
	public static final KeepAliveConfig  KEEP_ALIVE_CONFIG ;
	
	static{
		KEEP_ALIVE_CONFIG= new KeepAliveConfig();
		KEEP_ALIVE_CONFIG.setKeepAliveCheckRate(1000);
		KEEP_ALIVE_CONFIG.setKeepAliveTimeout(1000*60);
	}
	
	
	
	private final Node keepAliveHead;;
	private final Node keepAliveTail;
	private final long keepAliveTimeout;
	private final long keepAliveCheckRate;
	

	public HttpAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, SelectorProvider selectorProvider, Map<Object, Object> reses) {
		super(group, closeTask, selectorProvider, reses);
		keepAliveHead = new Node();
		keepAliveTail = new Node();
		initLinked(keepAliveHead, keepAliveTail);
		Object tmp = reses.get("keepAliveConfig");
		
		KeepAliveConfig kpcfg =( tmp !=null && tmp  instanceof KeepAliveConfig)?(KeepAliveConfig)tmp:KEEP_ALIVE_CONFIG;
		this.keepAliveCheckRate = kpcfg.getKeepAliveCheckRate();
		this.keepAliveTimeout = kpcfg.getKeepAliveTimeout();
		assert this.keepAliveCheckRate>0 && this.keepAliveTimeout >0 && this.keepAliveTimeout> this.keepAliveCheckRate && (this.keepAliveTimeout % this.keepAliveCheckRate ==0);
	}

	public void checkKeepAlive(HttpChannel channel){
		Node node = channel.getKeepAliveNode();
		node.prev = keepAliveTail.prev;
		node.next =keepAliveTail;
		keepAliveTail.prev = node;
		node.time = System.currentTimeMillis()+this.keepAliveCheckRate;
	}
	
	private AsyncTask keepAliveCheckTask = new AsyncTask() {
		
		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}
		
		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
            		Node node = keepAliveHead.next;
            		while(node!= keepAliveTail && System.currentTimeMillis() > node.time ){
            			((HttpChannel)node.item).close();
            		}
            		keepAliveHead.next = node;
            		node.prev =keepAliveHead;
		}
		
		@Override
		public void completed(AsyncExecutor executor) {
			executor.schedule(this,keepAliveCheckRate,TimeUnit.MILLISECONDS);			
		}	
		@Override
		public void cancled(AsyncExecutor executor) {
		}
	};
	@Override
	public List<AsyncTask> pendingTasks() {
		List<AsyncTask>ret  =super. pendingTasks();
		ret.add(new AsyncTask() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
			}
			@Override
			public void execute(AsyncExecutor executor) throws Throwable {				
				executor.schedule(keepAliveCheckTask, keepAliveCheckRate, TimeUnit.MILLISECONDS);
			}
			@Override
			public void completed(AsyncExecutor executor) {
			}
			
			@Override
			public void cancled(AsyncExecutor executor) {
			}
		});
		return ret;
	}

	
	
	public static class KeepAliveConfig{
		private  long keepAliveTimeout;
		private  long keepAliveCheckRate;
		public long getKeepAliveTimeout() {
			return keepAliveTimeout;
		}
		public void setKeepAliveTimeout(long keepAliveTimeout) {
			this.keepAliveTimeout = keepAliveTimeout;
		}
		public long getKeepAliveCheckRate() {
			return keepAliveCheckRate;
		}
		public void setKeepAliveCheckRate(long keepAliveCheckRate) {
			this.keepAliveCheckRate = keepAliveCheckRate;
		}
		
	}
	
}
