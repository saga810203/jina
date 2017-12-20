package org.jfw.jina.util.concurrent.spi;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncExecutorGroup;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.SystemPropertyUtil;

public abstract class AbstractAsyncExecutor implements AsyncExecutor, Runnable {
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

	protected LinkedList<AsyncTask> tasks = new LinkedList<AsyncTask>();
	protected ConcurrentLinkedQueue<AsyncTask> syncTask = new ConcurrentLinkedQueue<AsyncTask>();

	public AbstractAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask) {
		assert null != group;
		assert null != closeTask;
		this.group = group;
		this.closeTask = closeTask;

		hRTask = new Node();
		tRTask = new Node();
		hRTask.next = tRTask;
		tRTask.prev = hRTask;

		hQTask = new Node();
		tQTask = new Node();
		hQTask.next = tQTask;
		tQTask.prev = hQTask;

		hDTask = new Node();
		tDTask = new Node();
		hDTask.next = tDTask;
		tDTask.prev = hDTask;

		hSTask = new Node();
		tSTask = new Node();
		hSTask.next = tSTask;
		tSTask.prev = hSTask;
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
		this.submit(new AsyncTask() {
			public void failed(Throwable exc, AsyncExecutor executor) {
			}

			public void completed(AsyncExecutor executor) {
			}

			public void cancled(AsyncExecutor executor) {
			}

			public void execute(AsyncExecutor executor) throws Throwable {
				if (state <= ST_STARTED) {
					state = ST_SHUTTING_DOWN;
				}
			}
		});
	}

	public void submit(final AsyncTask task) {
		Node node = getNode();
		node.item = task;
		if (this.inLoop()) {
			++numQTask;
			tQTask.prev.next = node;
			node.next = tQTask;
		} else {
			lock.lock();
			try {
				++nSTask;
				tSTask.prev.next = node;
				node.next = tSTask;
			} finally {
				lock.unlock();
			}
			if (this.state == ST_NOT_STARTED) {
				if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
					this.doStartThread();
				}
			}
		}
	}

	public void doStartThread() {
		new Thread(this).start();
	}

	public void schedule(final AsyncTask task, final long delay, final TimeUnit unit) {
		if (this.inLoop()) {
			long time = unit.toNanos(delay);
			Node node = getNode();
			node.item = task;
			time = node.time = System.nanoTime() - START_TIME + time;

			Node end = hDTask.next;
			while (end != tDTask) {
				if (time > end.time) {
					break;
				}
			}
			end.prev.next = node;
			node.next = end;
			++numDTask;
		} else {
			this.submit(new AsyncTask() {
				public void failed(Throwable exc, AsyncExecutor executor) {
				}

				public void execute(AsyncExecutor executor) throws Throwable {
					schedule(task, delay, unit);
				}

				public void completed(AsyncExecutor executor) {
				}

				public void cancled(AsyncExecutor executor) {
					task.cancled(executor);
				}
			});
			this.wakeup();
		}
	}

	protected abstract void wakeup();

	@Override
	public void run() {
		try {
			for (;;) {
				sTaskCopyToRunning();
				qTaskCopyToRunning();
				dTaskCopyToRunning();
				handleRunningTask();
				if (isShuttingDown()) {
					cancleRunningTask();
					cancleQueueTask();
					cancleDelayTask();
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

	public abstract void handleRunningTask();

	public abstract void cleanup();

	// ------------------------- sync Task----------------------------
	protected volatile int nSTask = 0;
	private ReentrantLock lock = new ReentrantLock(true);
	protected Node hSTask;
	protected Node tSTask;

	protected boolean hasSyncTask() {
		lock.lock();
		try {
			return nSTask > 0;
		} finally {
			lock.unlock();
		}
	}

	protected void sTaskCopyToRunning() {
		lock.lock();
		try {
			if (nSTask > 0) {
				nRTask += nSTask;
				tRTask.prev.next = hSTask.next;
				hSTask.next.prev = tRTask.prev;
				tRTask.prev = tSTask.prev;
				tRTask.prev.next = tRTask;
				hSTask.next = tSTask;
				tSTask.prev = hSTask;
				nSTask = 0;
			}
		} finally {
			lock.unlock();
		}
	}

	// ---------------------------delayed Task
	protected int numDTask = 0;
	protected Node hDTask;
	protected Node tDTask;

	protected boolean hasReadyDelayTask() {
		return numDTask > 0 && ((System.nanoTime() - START_TIME) >= hDTask.next.time);
	}

	protected void dTaskCopyToRunning() {
		if (numDTask > 0) {
			long dTime = System.nanoTime() - START_TIME;
			int match = 0;
			Node node = hDTask.next;
			while (node != tDTask && (node.time <= dTime)) {
				++match;
			}
			if (match > 0) {
				nRTask += match;
				numDTask -= match;
				tRTask.prev.next = hDTask.next;
				hDTask.next.prev = tRTask.prev;
				tRTask.prev = node.prev;
				tRTask.prev.next = tRTask;
				node.prev = hDTask;
				hDTask.next = node;
			}
		}
	}

	protected void cancleDelayTask() {
		assert inLoop();
		if (numDTask > 0) {
			Node node = hDTask.next;
			while (node != tDTask) {
				node.item.cancled(this);
			}
			numDTask = 0;
			replace(hDTask.next, tDTask.prev);
			hDTask.next = tDTask;
			tDTask.prev = hDTask;

		}
	}

	// ------------------------------queue Task----------------------
	protected int numQTask = 0;

	protected Node hQTask;
	protected Node tQTask;

	protected void qTaskCopyToRunning() {
		assert inLoop();
		if (numQTask > 0) {
			nRTask += numQTask;
			tRTask.prev.next = hQTask.next;
			hQTask.next.prev = tRTask.prev;
			tRTask.prev = tQTask.prev;
			tRTask.prev.next = tRTask;
			hQTask.next = tQTask;
			tQTask.prev = hQTask;
			numQTask = 0;
		}
	}

	protected void cancleQueueTask() {
		assert inLoop();
		if (numQTask > 0) {
			Node node = hQTask.next;
			while (node != tQTask) {
				node.item.cancled(this);
			}
			numQTask = 0;
			replace(hQTask.next, tQTask.prev);
			hQTask.next = tQTask;
			tQTask.prev = hQTask;

		}
	}

	// -----------------------------------------------------running task
	// ------------------------------------------------
	protected int nRTask = 0;

	protected Node hRTask = null;
	protected Node tRTask = null;

	// protected void addRunningTask(Node node){
	// ++nRTask;
	// tRTask.prev.next= node;
	// node.next = tRTask;
	// }
	// protected void addRunningTask(Node begin ,Node end,int cnt){
	// nRTask +=cnt;
	// tRTask.prev.next= begin;
	// end.next = tRTask;
	// }

	protected void runRunningTasks() {
		assert inLoop();
		if (nRTask != 0) {
			Node node = hRTask.next;
			while (node != tRTask) {
				AsyncTask task = node.item;
				try {
					task.execute(this);
					task.completed(this);
				} catch (Throwable exc) {
					task.failed(exc, this);
				}
				node = node.next;
			}
			replace(hRTask.next, tRTask.prev);
			nRTask = 0;
			hRTask.next = tRTask;
			tRTask.prev = hRTask;
		}
	}

	protected void cancleRunningTask() {
		assert inLoop();
		if (nRTask != 0) {
			Node node = hRTask.next;
			while (node != tRTask) {
				node.item.cancled(this);
				node = node.next;
			}
			replace(hRTask.next, tRTask.prev);
			nRTask = 0;
			hRTask.next = tRTask;
			tRTask.prev = hRTask;
		}
	}

	protected void runRunningTasks(long time) {
		assert inLoop();
		if (nRTask != 0) {
			int cnt = 0;
			long bTime = System.nanoTime();
			Node node = hRTask.next;
			while (node != tRTask && ((System.nanoTime() - bTime)) < time) {
				AsyncTask task = node.item;
				try {
					task.execute(this);
					task.completed(this);
				} catch (Throwable exc) {
					task.failed(exc, this);
				}
				++cnt;
				node = node.next;
			}

			replace(hRTask.next, node.prev);
			nRTask -= cnt;
			hRTask.next = node;
			node.prev = hRTask;
		}
	}

	// ------------------------------------------ Node
	// Pool--------------------------------------
	private static final int MAX_NUM_POOLED_NODE = SystemPropertyUtil.getInt("org.jfw.jina.util.concurrent.spi.AbstractAsyncTaskExecutor.MAX_NUM_POOLED_NODE",
			1024);

	private int nPNode = 0;
	private final Node hPNode = new Node();

	protected Node getNode() {
		Node ret = hPNode.next;
		if (null == ret) {
			ret = new Node();
		} else {
			hPNode.next = ret.next;
			--nPNode;
		}
		return ret;
	}

	protected void replace(Node node) {
		if (nPNode < MAX_NUM_POOLED_NODE) {
			++nPNode;
			node.next = hPNode.next;
			hPNode.next = node;
		}
		node.item = null;
		node.prev = null;
	}

	protected void replace(Node begin, Node end) {
		end.next = null;
		begin.prev = null;
		int cnt = 0;
		Node n2;
		Node node = begin.next;
		while (node != null) {
			++cnt;
			node.item = null;
			node = node.next;
		}
		if (nPNode < MAX_NUM_POOLED_NODE) {
			end.next = hPNode.next;
			hPNode.next = begin;
			nPNode += cnt;
		} else {
			node = begin;
			while (node != null) {
				n2 = node;
				node = node.next;
				n2.prev = null;
				n2.next = null;
			}
		}
	}

	protected static class Node {
		AsyncTask item;
		Node next;
		Node prev;
		long time;
	}
}
