package org.jfw.jina.core.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncExecutorGroup;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.AsyncTaskAdapter;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.Matcher;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.QueueProvider;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.impl.QueueProviderImpl;

public abstract class AbstractAsyncExecutor extends QueueProviderImpl implements AsyncExecutor, QueueProvider, Runnable {
	public static final long START_TIME = System.nanoTime();
	public static final int ST_NOT_STARTED = 1;
	public static final int ST_STARTED = 2;
	public static final int ST_SHUTTING_DOWN = 3;
	public static final int ST_SHUTDOWN = 4;
	// public static final int ST_TERMINATED = 5;
	private static final AtomicIntegerFieldUpdater<AbstractAsyncExecutor> STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(AbstractAsyncExecutor.class,
			"state");

	private final AsyncExecutorGroup group;
	protected final Runnable closeTask;
	protected volatile Thread thread;
	protected volatile int state = ST_NOT_STARTED;

	protected ArrayList<Object> objCache = new ArrayList<Object>();

	protected Queue<AsyncTask> runningTasks = null;
	private Queue<AsyncTask> waitTasks = null;
	private TagQueue<AsyncTask, Long> delayTasks = null;
	private Queue<AsyncTask> syncTasks = null;

	public AbstractAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask) {
		assert null != group;
		assert null != closeTask;
		this.group = group;
		this.closeTask = closeTask;
		runningTasks = this.newQueue();
		waitTasks = this.newQueue();
		delayTasks = this.newTagQueue();
		syncTasks = this.newQueue();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T get(int idx) {
		assert this.inLoop();
		return (T) objCache.get(idx);
	}

	@Override
	public int set(Object val) {
		assert inLoop();
		objCache.add(val);
		return objCache.indexOf(val);
	}

	public AsyncExecutorGroup group() {
		return group;
	}

	public boolean inLoop() {
		return this.thread == Thread.currentThread();
	}

	public boolean isShuttingDown() {
		return state >= ST_SHUTTING_DOWN;
	}

	public void shutdown() {
		this.submit(new AsyncTaskAdapter() {
			public void execute(AsyncExecutor executor) {
				if (state <= ST_STARTED) {
					state = ST_SHUTTING_DOWN;
				}
			}
		});
	}

	public void submit(final AsyncTask task) {
		if (this.inLoop()) {
			waitTasks.offer(task);
		} else {
			if (isShuttingDown()) {
				task.cancled(this);
				return;
			}
			lock.lock();
			try {
				if (isShuttingDown()) {
					task.cancled(this);
					return;
				}
				syncTasks.offer(task);
			} finally {
				lock.unlock();
			}

		}
	}

	public void submitInLoop(AsyncTask task) {
		assert this.inLoop();
		waitTasks.offer(task);
	}

	protected Long nextScheduleDeadLine() {
		return (Long) delayTasks.peekTag();
	}

	private static final Comparator<Long> delayTaskComparator = new Comparator<Long>() {
		@Override
		public int compare(Long o1, Long o2) {
			long ret = o1.longValue() - o2.longValue();
			return ret > 0 ? 1 : (ret == 0 ? 0 : -1);
		}
	};

	public void schedule(final AsyncTask task, final long delay, final TimeUnit unit) {
		if (this.inLoop()) {
			long time = System.nanoTime() - START_TIME + unit.toNanos(delay);
			delayTasks.beforeWithTag(task, Long.valueOf(time), delayTaskComparator);
		} else {
			this.submit(new AsyncTaskAdapter() {
				public void execute(AsyncExecutor executor) throws Throwable {
					schedule(task, delay, unit);
				}

				public void cancled(AsyncExecutor executor) {
					task.cancled(executor);
				}
			});
			this.wakeup();
		}
	}

	public void scheduleInLoop(AsyncTask task, long delay, TimeUnit unit) {
		assert this.inLoop();
		long time = System.nanoTime() - START_TIME + unit.toNanos(delay);
		delayTasks.beforeWithTag(task, Long.valueOf(time), delayTaskComparator);
	}

	protected abstract void wakeup();

	@Override
	public final void run() {
		if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
			try {
				thread = Thread.currentThread();
				((ProxyAsyncExecutor) thread).setExecutor(this);
				for (AsyncTask task : pendingTasks()) {
					safeInvoke(task, this);
				}
				for (;;) {
					lock.lock();
					try {
						syncTasks.offerTo(runningTasks);
					} finally {
						lock.unlock();
					}
					waitTasks.offerTo(runningTasks);
					delayTasks.offerToWithTag(runningTasks, delyTaskHandlerMatcher);
					handleRunningTask();
					if (isShuttingDown()) {
						lock.lock();
						try {
							syncTasks.offerTo(waitTasks);
						} finally {
							lock.unlock();
						}
						runningTasks.clear(CANCEL_HANDLER);
						waitTasks.clear(CANCEL_HANDLER);
						delayTasks.clear(CANCEL_HANDLER);

						break;
					}
				}
			} finally {
				for (;;) {
					int oldState = state;
					if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(this, oldState, ST_SHUTTING_DOWN)) {
						break;
					}
				}
				this.cleanup();
				try {
					this.closeTask.run();
				} catch (Throwable exc) {
				}
			}
		}
	}

	public abstract void handleRunningTask();

	public abstract List<AsyncTask> pendingTasks();

	public abstract void cleanup();

	private ReentrantLock lock = new ReentrantLock(true);

	protected boolean hasSyncTask() {
		lock.lock();
		try {
			return !syncTasks.isEmpty();
		} finally {
			lock.unlock();
		}
	}

	protected boolean hasReadyDelayTask() {
		Long time = (Long) delayTasks.peekTag();
		return time != null && ((System.nanoTime() - START_TIME - time) >= 0);
	}

	private final Matcher<Long> delyTaskHandlerMatcher = new Matcher<Long>() {
		@Override
		public boolean match(Long tag) {
			return System.nanoTime() - START_TIME - tag.longValue() >= 0;
		}
	};

	protected final Handler<AsyncTask> CANCEL_HANDLER = new Handler<AsyncTask>() {
		@Override
		public void process(AsyncTask item) {
			item.cancled(AbstractAsyncExecutor.this);
		}
	};

	protected final Handler<AsyncTask> RUN_HANDLER = new Handler<AsyncTask>() {
		@Override
		public void process(AsyncTask item) {
			safeInvoke(item,  AbstractAsyncExecutor.this);
		}
	};

	protected void runRunningTasks() {
		assert inLoop();
		runningTasks.clear(RUN_HANDLER);
	}

	// protected void cancleRunningTask() {
	// assert inLoop();
	// runningTasks.processAndFree(CANCEL_HANDLER);
	// }

	protected final class TimeRunHandler implements Matcher<AsyncTask> {
		private long time;
		private long begin;

		@Override
		public boolean match(AsyncTask item) {
			if (System.nanoTime() - begin < time) {
				safeInvoke(item,  AbstractAsyncExecutor.this);
				return true;
			} else {
				return false;
			}
		}
	}

	protected final TimeRunHandler TIME_RUN_HANDLER = new TimeRunHandler();

	protected void runRunningTasks(final long time) {
		assert inLoop();
		TIME_RUN_HANDLER.time = time;
		TIME_RUN_HANDLER.begin = System.nanoTime();
		runningTasks.clear(TIME_RUN_HANDLER);
	}

	public static  void safeInvoke(AsyncTask task,AsyncExecutor executor){
		try{
			task.execute(executor);
		}catch(Throwable e){
			task.failed(e, executor);
			return;
		}
		try{
			task.completed(executor);
		}catch(Throwable e){
		}
	}
	public static void safeCancle(AsyncTask task,AsyncExecutor executor){
		try{
			task.cancled(executor);
		}catch(Throwable e){
		}
	}

}
