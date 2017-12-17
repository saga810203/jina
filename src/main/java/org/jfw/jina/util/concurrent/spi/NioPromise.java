package org.jfw.jina.util.concurrent.spi;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.jfw.jina.util.concurrent.EventExecutor;
import org.jfw.jina.util.concurrent.Promise;
import org.jfw.jina.util.concurrent.PromiseListener;

public class NioPromise<V> implements Promise<V>, Runnable {

	private int state = Promise.STATE_INIT;

	private Queue<PromiseListener<NioPromise<V>>> lsts = new LinkedList<PromiseListener<NioPromise<V>>>();

	private final EventExecutor executor;

	@SuppressWarnings("rawtypes")
	private static final AtomicIntegerFieldUpdater<NioPromise> STATE_UPDATER = AtomicIntegerFieldUpdater
			.newUpdater(NioPromise.class, "state");

	private Object result;
	private Runnable runner;
	private Callable<V> caller;

	public NioPromise(EventExecutor executor, Runnable runner, V result) {
		assert runner != null;
		this.executor = executor;
		this.result = result;
		this.runner = runner;
		this.caller = null;
	}

	public NioPromise(EventExecutor executor, Callable<V> caller) {
		assert caller != null;
		this.executor = executor;
		this.result = null;
		this.runner = null;
		this.caller = caller;
	}

	public int state() {
		return state;
	}

	public void cancel() {
		if (executor.inEventLoop()) {
			this.cancelInLoop();
		} else {
			this.executor.submit(new Runnable() {
				public void run() {
					cancelInLoop();
				}
			});
		}
	}

	private void cancelInLoop() {
		assert executor.inEventLoop();
		if (STATE_UPDATER.compareAndSet(this, Promise.STATE_INIT, Promise.STATE_CANCELED)) {
			STATE_UPDATER.set(this, Promise.STATE_CANCELED);
			this.notifyListeners();
		}
	}

	public boolean isCancelled() {
		return state == Promise.STATE_CANCELED;
	}

	@SuppressWarnings("unchecked")
	public V get() {
		return (V) result;
	}

	@SuppressWarnings("rawtypes")
	public Throwable cause() {
		return (Throwable) result;
	}

	public void addListener(final PromiseListener<NioPromise<V>> listener) {
		if(this.executor.inEventLoop()){
			this.addListenerInLoop(listener);
		}else{
			this.executor.submit(new Runnable() {
				public void run() {
					addListenerInLoop(listener);
				}
			});
		}
	}

	private void addListenerInLoop(PromiseListener<NioPromise<V>> listener) {
		if (state > Promise.STATE_UNCANCELLABLE) {
			listener.complete(this);
		} else {
			lsts.add(listener);
		}
	}

	public void addListener(final PromiseListener<NioPromise<V>>... listeners) {
		if(this.executor.inEventLoop()){
			this.addListenerInLoop(listeners);
		}else{
			this.executor.submit(new Runnable() {
				public void run() {
					addListenerInLoop(listeners);
				}
			});
		}
	}
	
	private void addListenerInLoop(PromiseListener<NioPromise<V>>... listeners) {
		assert this.executor.inEventLoop();
		if (state > Promise.STATE_UNCANCELLABLE) {
			for(PromiseListener<NioPromise<V>> lst:listeners){
				lst.complete(this);
			}
		} else {
			for(PromiseListener<NioPromise<V>> lst:listeners){
				lsts.add(lst);
			}
		}
	}

	private void notifyListeners() {
		for (PromiseListener<NioPromise<V>> listener : lsts) {
			listener.complete(this);
		}
	}

	public void run() {
		if (this.executor.inEventLoop() && this.state == Promise.STATE_INIT) {
			this.state = Promise.STATE_UNCANCELLABLE;
			try {
				if (null != this.runner) {
					this.runner.run();
				} else {
					this.result = this.caller.call();
				}
				this.state = Promise.STATE_SUCCESS;
			} catch (Throwable t) {
				this.result = t;
				this.state = Promise.STATE_FAIL;
			} finally {
				notifyListeners();
			}
		}
	}

	public void addListener(PromiseListener<? extends Promise<? super V>> listener) {
		// TODO Auto-generated method stub

	}

	public void addListener(PromiseListener<? extends Promise<? super V>>... listeners) {
		// TODO Auto-generated method stub

	}

}
