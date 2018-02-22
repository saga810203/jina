package org.jfw.jina.core.impl;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncExecutorGroup;
import org.jfw.jina.core.AsyncTask;

public  class ProxyAsyncExecutor extends Thread implements AsyncExecutor {

	@SuppressWarnings("unchecked")
	public static <T extends AsyncExecutor> T executor() {
		Thread thread = Thread.currentThread();
		if (thread instanceof ProxyAsyncExecutor) {
			return (T) ((ProxyAsyncExecutor) thread).executor;
		}
		return null;
	}

	private static final AtomicIntegerFieldUpdater<ProxyAsyncExecutor> STARTED_UPDATER = AtomicIntegerFieldUpdater.newUpdater(ProxyAsyncExecutor.class,
			"started");

	@SuppressWarnings("unused")
	private  volatile int started = 0;

	private volatile AsyncExecutor groupChildren[];
	private volatile int idxInGroupChildren;
	private volatile AbstractAsyncExecutorGroup group;
	private AsyncExecutor executor = null;

	public void setExecutor(AsyncExecutor executor){
		if(this!= Thread.currentThread()){
			throw new UnsupportedOperationException();
		}
		this.executor = executor;
	}
	
	
	public ProxyAsyncExecutor(AsyncExecutor[] groupChildren, int idxInGroupChildren, AbstractAsyncExecutorGroup group) {
		super();
		this.groupChildren = groupChildren;
		this.idxInGroupChildren = idxInGroupChildren;
		this.group = group;
	}

	@Override
	public AsyncExecutorGroup group() {
		return this.group;
	}

	@Override
	public boolean inLoop() {
		throw new UnsupportedOperationException();
	}

	private AsyncExecutor getOriginal() {
		long start = System.currentTimeMillis();
		int i = 0;
		for (;;) {
			if (System.currentTimeMillis() - start > (1000 * 10)) {
				throw new RuntimeException("I am sorry, I don't know this Error");
			}
			AsyncExecutor ret = groupChildren[idxInGroupChildren];
			if (ret == null || this == ret) {
				++i;
				if (i % 2 == 0) {
					try {
						Thread.sleep(10);
					} catch (Throwable e) {
					}
				} else {
					Thread.yield();
				}
			} else {
				return ret;
			}
		}
	}

	@Override
	public void shutdown() {
		for (;;) {
			int s = STARTED_UPDATER.get(this);
			if (s == 2) {
				// throw new IllegalStateException();
				return;
			} else if (s == 1) {
				getOriginal().shutdown();
				return;
			} else {
				if (STARTED_UPDATER.compareAndSet(this, 0, 2)) {
					return;
				}
			}
		}
	}

	@Override
	public void submit(AsyncTask task) {
		for (;;) {
			int s = STARTED_UPDATER.get(this);
			if (s == 2) {
				// throw new IllegalStateException();
				return;
			} else if (s == 1) {
				getOriginal().submit(task);
				return;
			} else {
				if (STARTED_UPDATER.compareAndSet(this, 0, 1)) {
					this.start();
					Thread.yield();
				}
			}
		}
	}

	@Override
	public void schedule(AsyncTask task, long delay, TimeUnit unit) {
		for (;;) {
			int s = STARTED_UPDATER.get(this);
			if (s == 2) {
				// throw new IllegalStateException();
				return;
			} else if (s == 1) {
				getOriginal().schedule(task, delay, unit);
				return;
			} else {
				if (STARTED_UPDATER.compareAndSet(this, 0, 1)) {
					this.start();
					Thread.yield();
				}
			}
		}
	}

	@Override
	public <T> T get(int idx) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int set(Object val) {
		throw new UnsupportedOperationException();
	}

	public final void run() {
		AsyncExecutor ae = this.group.newChild(idxInGroupChildren);
		groupChildren[idxInGroupChildren] = ae;
		this.executor = ae;
		if (ae instanceof Runnable) {
			((Runnable) ae).run();
		}
	}
}
