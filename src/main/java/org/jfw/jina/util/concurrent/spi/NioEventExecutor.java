package org.jfw.jina.util.concurrent.spi;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.jfw.jina.util.concurrent.EventExecutor;
import org.jfw.jina.util.concurrent.EventExecutorGroup;
import org.jfw.jina.util.concurrent.Promise;
import org.jfw.jina.util.concurrent.PromiseListener;
import org.jfw.jina.util.concurrent.ScheduledPromise;

public class NioEventExecutor<T extends Thread> implements EventExecutor {
	
	
	private final EventExecutorGroup group;
	
	private T thread; 
	private Queue<Runnable> tasks = new LinkedList<Runnable>();
	private Queue<Runnable> bTasks= new LinkedList<Runnable>();
	
	
	public NioEventExecutor(EventExecutorGroup group){
		this.group = group;
	}

	public EventExecutorGroup group() {
		// TODO Auto-generated method stub
		return group;
	}

	public <V> Promise<V> newPromise() {
		// TODO Auto-generated method stub
		return null;
	}

	public <V> Promise<V> newSucceededPromise(V result) {
		// TODO Auto-generated method stub
		return null;
	}

	public <V> Promise<V> newFailedPromise(Throwable cause) {
		// TODO Auto-generated method stub
		return null;
	}

	public boolean inEventLoop() {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean isShuttingDown() {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean isShutdown() {
		// TODO Auto-generated method stub
		return false;
	}

	public void shutdown() {
		// TODO Auto-generated method stub
		
	}

	public Promise<?> terminationPromise() {
		// TODO Auto-generated method stub
		return null;
	}

	public Promise<?> submit(Runnable task) {
		// TODO Auto-generated method stub
		return null;
	}

	public <T> Promise<T> submit(Runnable task, T result) {
		// TODO Auto-generated method stub
		return null;
	}

	public <T> Promise<T> submit(Callable<T> task) {
		// TODO Auto-generated method stub
		return null;
	}

	public ScheduledPromise<?> schedule(Runnable command, long delay, TimeUnit unit) {
		// TODO Auto-generated method stub
		return null;
	}

	public <V> ScheduledPromise<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		// TODO Auto-generated method stub
		return null;
	}

	public ScheduledPromise<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		// TODO Auto-generated method stub
		return null;
	}

	public ScheduledPromise<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		// TODO Auto-generated method stub
		return null;
	}
	
	
	
}
