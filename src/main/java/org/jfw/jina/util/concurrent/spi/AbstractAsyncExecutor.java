package org.jfw.jina.util.concurrent.spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Handler;
import org.jfw.jina.util.common.Queue.Matcher;
import org.jfw.jina.util.common.Queue.Node;
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

	protected Map<Object, Object> objCache = new HashMap<Object, Object>();
	protected LinkedQueue runningTasks = new LinkedQueue();
	private LinkedQueue waitTasks = new LinkedQueue();
	private LinkedQueue delayTasks = new LinkedQueue();
	private LinkedQueue syncTasks = new LinkedQueue();

	public AbstractAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask) {
		assert null != group;
		assert null != closeTask;
		this.group = group;
		this.closeTask = closeTask;
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
		if (this.inLoop()) {
			waitTasks.add(task);
		} else {
			lock.lock();
			try {
				syncTasks.add(task);
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
		new ExecutorThread(this).start();
	}

	private class ScheduleHandler implements Handler {
		long time;
		LinkedNode target;

		@Override
		public void begin(Queue queue) {
		}

		@Override
		public boolean handle(Queue queue, Node node, Object item, Object tag) {
			if (((Long) tag).longValue() - time > 0) {
				((LinkedNode) node).before(target);
				target = null;
				return false;
			}
			return true;
		}

		@Override
		public void end(Queue queue) {
			if (target != null) {
				((LinkedQueue) queue).tail.before(target);
			}
		}
	}

	private final ScheduleHandler scheduleHandler = new ScheduleHandler();

	protected Long nextScheduleDeadLine() {
		return (Long) delayTasks.firstTag();
	}

	public void schedule(final AsyncTask task, final long delay, final TimeUnit unit) {
		if (this.inLoop()) {
			final long time = System.nanoTime() - START_TIME + unit.toNanos(delay);
			LinkedNode node = getNode();
			node.item = task;
			node.tag = time;
			scheduleHandler.target = node;
			scheduleHandler.time = time;
			this.delayTasks.process(scheduleHandler);
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

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getObject(Object key) {
		return (T) objCache.get(key);
	}

	public void setObject(Object key, Object val) {
		assert this.inLoop();
		assert objCache.get(key) == null;
		this.objCache.put(key, val);
	}

	@Override
	public final void run() {
		try {
			thread = Thread.currentThread();
			((ExecutorThread) thread).executor = this;
			for (AsyncTask task : pendingTasks()) {
				try {
					task.execute(this);
					task.completed(this);
				} catch (Throwable thr) {
					task.failed(thr, this);
				}
			}
			for (;;) {
				lock.lock();
				try {
					syncTasks.moveTo(runningTasks);
				} finally {
					lock.unlock();
				}
				waitTasks.moveTo(runningTasks);
				delayTasks.moveTo(runningTasks, matcher);
				handleRunningTask();
				if (isShuttingDown()) {
					runningTasks.processAndFree(CANCEL_HANDLER);
					waitTasks.processAndFree(CANCEL_HANDLER);
					delayTasks.processAndFree(CANCEL_HANDLER);
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
		Long time = (Long) delayTasks.firstTag();
		return time != null && ((System.nanoTime() - START_TIME - time) >= 0);
	}

	private final Matcher matcher = new Matcher() {
		@Override
		public boolean match(Queue queue, Node node, Object item, Object tag) {
			return System.nanoTime() - START_TIME - ((Long) tag) >= 0;
		}
	};

	protected final Handler CANCEL_HANDLER = new Handler() {
		@Override
		public boolean handle(Queue queue, Node node, Object item, Object tag) {
			((AsyncTask) item).cancled(AbstractAsyncExecutor.this);
			return true;
		}

		@Override
		public void end(Queue queue) {
		}

		@Override
		public void begin(Queue queue) {
		}
	};

	protected final Queue.Handler RUN_HANDLER = new Handler() {
		@Override
		public boolean handle(Queue queue, Node node, Object item, Object tag) {
			AsyncTask task = (AsyncTask) item;
			try {
				task.execute(AbstractAsyncExecutor.this);
				task.completed(AbstractAsyncExecutor.this);
			} catch (Throwable exc) {
				task.failed(exc, AbstractAsyncExecutor.this);
			}
			return true;
		}

		@Override
		public void end(Queue queue) {
		}

		@Override
		public void begin(Queue queue) {
		}
	};

	protected void runRunningTasks() {
		assert inLoop();
		runningTasks.processAndFree(RUN_HANDLER);
	}

	// protected void cancleRunningTask() {
	// assert inLoop();
	// runningTasks.processAndFree(CANCEL_HANDLER);
	// }

	protected final class TimeRunHandler implements Handler {
		private long time;
		private long begin;

		@Override
		public void begin(Queue queue) {
			begin = System.nanoTime();
		}

		@Override
		public boolean handle(Queue queue, Node node, Object item, Object tag) {
			AsyncTask task = (AsyncTask) item;
			try {
				task.execute(AbstractAsyncExecutor.this);
				task.completed(AbstractAsyncExecutor.this);
			} catch (Throwable exc) {
				task.failed(exc, AbstractAsyncExecutor.this);
			}
			return System.nanoTime() - begin < time;
		}

		@Override
		public void end(Queue queue) {
		}

	}

	protected final TimeRunHandler TIME_RUN_HANDLER = new TimeRunHandler();

	protected void runRunningTasks(final long time) {
		assert inLoop();
		TIME_RUN_HANDLER.time = time;
		runningTasks.processAndFree(TIME_RUN_HANDLER);
	}

	// ------------------------------------------ Node
	// Pool--------------------------------------
	private static final int MAX_NUM_POOLED_NODE = SystemPropertyUtil.getInt("org.jfw.jina.util.concurrent.spi.AbstractAsyncTaskExecutor.MAX_NUM_POOLED_NODE",
			1024 * 1024);

	private int nPNode = 0;
	private final LinkedNode hPNode = new LinkedNode();

	public LinkedNode getNode() {
		LinkedNode ret = hPNode.next;
		if (null == ret) {
			ret = new LinkedNode();
		} else {
			hPNode.next = ret.next;
			--nPNode;
		}
		return ret;
	}

	public void release(LinkedNode node) {
		int cnt = 1;
		LinkedNode end = node;
		while (end.next != null) {
			++cnt;
			end.item = null;
			end.tag = null;
		}

		if (nPNode < MAX_NUM_POOLED_NODE) {
			nPNode += cnt;
			end.next = hPNode.next;
			hPNode.next = node;
		}
	}
	public void releaseSingle(LinkedNode node){
		
	}

	public Queue newQueue() {
		return new LinkedQueue();
	}

	public class LinkedQueue implements Queue {
		private final LinkedNode head;
		private final LinkedNode tail;

		
		@Override
		public boolean isEmpty() {
			return head.next == tail;
		}

		@Override
		public LinkedNode first() {
			LinkedNode ret = head.next;
			return ret == tail ? null : ret;
		}

		@Override
		public Object firstValue() {
			LinkedNode ret = head.next;
			return ret == tail ? null : ret.item;
		}

		@Override
		public Object firstTag() {
			LinkedNode ret = head.next;
			return ret == tail ? null : ret.tag;
		}

		public LinkedQueue() {
			this.head = new LinkedNode();
			this.tail = new LinkedNode();
			this.head.item = null;
			this.tail.item = null;
			this.head.prev = null;
			this.head.next = this.tail;
			this.tail.next = null;
			this.tail.prev = this.head;
		}

		@Override
		public void clear() {
			if (this.head.next != this.tail) {
				this.removeAndFree(this.head.next, this.tail.prev);
			}
		}

		@Override
		public Node add(Object item, Object tag) {
			LinkedNode node = getNode();
			node.item = item;
			node.tag = tag;
			this.tail.before(node);
			return node;
		}

		@Override
		public Node add(Object item) {
			LinkedNode node = getNode();
			node.item = item;
			this.tail.before(node);
			return node;
		}

		@Override
		public Node before(Node node, Object item, Object tag) {
			assert node instanceof LinkedNode;
			LinkedNode ln = (LinkedNode) node;
			LinkedNode ret = getNode();
			ret.item = item;
			ret.tag = tag;
			ln.before(ret);
			return ret;
		}

		@Override
		public Node after(Node node, Object item, Object tag) {
			assert node instanceof LinkedNode;
			LinkedNode ln = (LinkedNode) node;
			LinkedNode ret = getNode();
			ret.item = item;
			ret.tag = tag;
			ln.after(ret);
			return ret;
		}

		@Override
		public void remove(Node node) {
			assert node instanceof LinkedNode;
			LinkedNode ln = (LinkedNode) node;
			ln.next.prev = ln.prev;
			ln.prev.next = ln.next;
			ln.prev = null;
			ln.next = null;
		}

		@Override
		public void remove(Node begin, Node end) {
			assert begin instanceof LinkedNode;
			assert end instanceof LinkedNode;
			LinkedNode bl = ((LinkedNode) begin);
			LinkedNode el = ((LinkedNode) end);
			bl.prev.next = el.next;
			el.next.prev = bl.prev;
			bl.prev = null;
			el.next = null;
		}

		@Override
		public void process(Handler h) {
			h.begin(this);
			try {
				LinkedNode ln = head.next;
				while (ln != tail) {
					if (!h.handle(this, ln, ln.item, ln.tag)) {
						break;
					} else {
						ln = ln.next;
					}
				}
			} finally {
				h.end(this);
			}
		}

		@Override
		public void removeAndFree(Node node) {
			assert node instanceof LinkedNode;
			this.remove(node);
			releaseSingle((LinkedNode) node);
		}

		@Override
		public void removeAndFree(Node begin, Node end) {
			assert begin instanceof LinkedNode;
			assert end instanceof LinkedNode;
			this.remove(begin, end);
			release((LinkedNode) begin);
		}

		@Override
		public void processAndFree(Handler h) {
			LinkedNode ln = head.next;
			h.begin(this);
			try {
				while (ln != tail) {
					if (!h.handle(this, ln, ln.item, ln.tag)) {
						break;
					} else {
						ln = ln.next;
					}
				}
			} finally {
				h.end(this);
			}
			LinkedNode n = head.next;
			ln.prev.next = null;
			head.next = ln;
			ln.prev = head;
			release(n);
		}

		@Override
		public void moveTo(Queue queue) {
			assert queue instanceof LinkedQueue;
			LinkedQueue lq = (LinkedQueue) queue;
			LinkedNode node = this.head.next;
			if (node != this.tail) {
				lq.tail.before(node, this.tail.prev);
				this.head.next = this.tail;
				this.tail.prev = this.head;
			}
		}

		@Override
		public void moveTo(Queue queue, Matcher m) {
			assert queue instanceof LinkedQueue;
			LinkedQueue lq = (LinkedQueue) queue;
			LinkedNode ln = head.next;
			try {
				while (ln != tail) {
					if (!m.match(this, ln, ln.item, ln.tag)) {
						break;
					} else {
						ln = ln.next;
					}
				}
			} finally {
				if (ln != head.next) {
					lq.tail.before(head.next, ln.prev);
					head.next = ln;
					ln.prev = head;
				}
			}
		}
	}

	public static class LinkedNode implements Queue.Node {
		public LinkedNode prev;
		public LinkedNode next;
		public Object item;
		public Object tag;

		public void before(LinkedNode node) {
			LinkedNode ob = this.prev;
			ob.next = node;
			node.prev = ob;
			node.next = this;
			this.prev = ob;
		}
		
		

		public void after(LinkedNode node) {
			LinkedNode oa = this.next;
			oa.prev = node;
			node.next = oa;
			this.next = node;
			node.prev = this;
		}

		// void after(LinkedNode begin,LinkedNode end){
		// if(begin == end){
		// after(begin);
		// }else{
		// LinkedNode oa = this.next;
		// this.next = begin;
		// begin.prev = this;
		// end.next = oa;
		// oa.prev = end;
		// }
		// }
		public void before(LinkedNode begin, LinkedNode end) {
			if (begin == end) {
				this.before(begin);
			} else {
				LinkedNode ob = this.prev;
				ob.next = begin;
				begin.prev = ob;
				end.next = this;
				this.prev = end;
			}
		}
	}

	public static class ExecutorThread extends Thread {
		private AbstractAsyncExecutor executor;

		public ExecutorThread(Runnable target) {
			super(target);
		}

		public AbstractAsyncExecutor getExecutor() {
			return executor;
		}

		public void setExecutor(AbstractAsyncExecutor executor) {
			this.executor = executor;
		}

		public static AbstractAsyncExecutor executor() {
			Thread thread = Thread.currentThread();
			if (thread instanceof ExecutorThread)
				return ((ExecutorThread) thread).executor;
			return null;
		}
	}
}
