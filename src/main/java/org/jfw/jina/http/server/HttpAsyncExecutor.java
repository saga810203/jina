package org.jfw.jina.http.server;

import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncExecutorGroup;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.AsyncTaskAdapter;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.util.DateFormatter;
import org.jfw.jina.util.DQueue;
import org.jfw.jina.util.Matcher;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public class HttpAsyncExecutor extends NioAsyncExecutor {

	public static final KeepAliveConfig KEEP_ALIVE_CONFIG;

	static {
		KEEP_ALIVE_CONFIG = new KeepAliveConfig();
		KEEP_ALIVE_CONFIG.setKeepAliveCheckRate(1000);
		KEEP_ALIVE_CONFIG.setKeepAliveTimeout(1000 * 20);
	}

	private final DQueue keepAliveQueue;
	private final long keepAliveTimeout;
	private final long keepAliveCheckRate;	
	public HttpAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, AsyncExecutor[] groupChildren,
			int idxInGroupChildren, SelectorProvider selectorProvider) {
		super(group, closeTask,selectorProvider);
		Object tmp = group.getParameter("http.keepAliveConfig");
		KeepAliveConfig kpcfg = (tmp != null && tmp instanceof KeepAliveConfig) ? (KeepAliveConfig) tmp : KEEP_ALIVE_CONFIG;
		this.keepAliveCheckRate = kpcfg.getKeepAliveCheckRate();
		this.keepAliveTimeout = kpcfg.getKeepAliveTimeout();
		assert this.keepAliveCheckRate > 0 && this.keepAliveTimeout > 0 && this.keepAliveTimeout > this.keepAliveCheckRate
				&& (this.keepAliveTimeout % this.keepAliveCheckRate == 0);
		this.keepAliveQueue = this.newDQueue();
	}

	public long getKeepAliveTimeout() {
		return keepAliveTimeout;
	}

	public DQueue getKeepAliveQueue() {
		return keepAliveQueue;
	}

	private final Matcher<Object> keepAliveCheckHandler = new Matcher<Object>() {
		@Override
		public boolean match(Object item) {
			KeepAliveCheck kac =(KeepAliveCheck)item;
			
			if(System.currentTimeMillis()-kac.getKeepAliveTime() > keepAliveTimeout){
				kac.keepAliveTimeout();
				return true;
			}
			return false;
		}
	};

	private AsyncTask keepAliveCheckTask = new AsyncTaskAdapter() {
		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
			keepAliveQueue.remove(keepAliveCheckHandler);
		}
		@Override
		public void completed(AsyncExecutor executor) {
			executor.schedule(this, keepAliveCheckRate, TimeUnit.MILLISECONDS);
		}

	};

	@Override
	public List<AsyncTask> pendingTasks() {
		List<AsyncTask> ret = super.pendingTasks();
		ret.add(new AsyncTaskAdapter() {
			@Override
			public void execute(AsyncExecutor executor) throws Throwable {
				executor.schedule(keepAliveCheckTask, keepAliveCheckRate, TimeUnit.MILLISECONDS);
			}
		});
		return ret;
	}

	public static class KeepAliveConfig {
		private long keepAliveTimeout;
		private long keepAliveCheckRate;

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

	public final DateFormatter dateFormatter = new DateFormatter();
}
