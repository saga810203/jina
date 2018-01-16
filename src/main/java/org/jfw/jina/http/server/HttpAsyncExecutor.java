package org.jfw.jina.http.server;

import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jfw.jina.http.util.DateFormatter;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Handler;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncExecutorGroup;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public class HttpAsyncExecutor extends NioAsyncExecutor {

	public static final KeepAliveConfig KEEP_ALIVE_CONFIG;

	static {
		KEEP_ALIVE_CONFIG = new KeepAliveConfig();
		KEEP_ALIVE_CONFIG.setKeepAliveCheckRate(1000);
		KEEP_ALIVE_CONFIG.setKeepAliveTimeout(1000 * 60);
	}

	private final Queue keepAliveQueue;
	private final long keepAliveTimeout;
	private final long keepAliveCheckRate;

	public HttpAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, SelectorProvider selectorProvider, Map<Object, Object> reses) {
		super(group, closeTask, selectorProvider, reses);
		this.keepAliveQueue = this.newQueue();
		Object tmp = reses.get("keepAliveConfig");

		KeepAliveConfig kpcfg = (tmp != null && tmp instanceof KeepAliveConfig) ? (KeepAliveConfig) tmp : KEEP_ALIVE_CONFIG;
		this.keepAliveCheckRate = kpcfg.getKeepAliveCheckRate();
		this.keepAliveTimeout = kpcfg.getKeepAliveTimeout();
		assert this.keepAliveCheckRate > 0 && this.keepAliveTimeout > 0 && this.keepAliveTimeout > this.keepAliveCheckRate
				&& (this.keepAliveTimeout % this.keepAliveCheckRate == 0);
	}

	public long getKeepAliveTimeout() {
		return keepAliveTimeout;
	}

	public Queue getKeepAliveQueue() {
		return keepAliveQueue;
	}

	private final Handler keepAliveCheckHandler = new Handler() {
		@Override
		public void begin(Queue queue) {
		}

		@Override
		public boolean handle(Queue queue, Node node, Object item, Object tag) {
			Long time = (Long) tag;
			HttpChannel channel = (HttpChannel) item;
			if (System.currentTimeMillis() >= time) {
				channel.keepAliveTimeout();
				return true;
			}
			return false;
		}
		@Override
		public void end(Queue queue) {
		}
	};

	private AsyncTask keepAliveCheckTask = new AsyncTask() {
		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}
		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
			keepAliveQueue.processAndFree(keepAliveCheckHandler);
		}
		@Override
		public void completed(AsyncExecutor executor) {
			executor.schedule(this, keepAliveCheckRate, TimeUnit.MILLISECONDS);
		}
		@Override
		public void cancled(AsyncExecutor executor) {
		}
	};

	@Override
	public List<AsyncTask> pendingTasks() {
		List<AsyncTask> ret = super.pendingTasks();
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
