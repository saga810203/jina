package org.jfw.jina.util.concurrent.spi;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncTask;

public final class DelayedNode  implements Delayed{
	private static final AtomicLong nextTaskId = new AtomicLong();
	public static final long START_TIME = System.nanoTime();
	public static final long SUB_START_TIME = - START_TIME;
	
//	static long nanoTime() {
//		return System.nanoTime() - START_TIME;
//	}

	static long deadlineNanos(long delay) {
		return  System.nanoTime() - START_TIME + delay;
	}

	private final long id = nextTaskId.getAndIncrement();
	private final long deadlineNanos;
//	/* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
//	private final long periodNanos;
	private final AsyncTask task;
	
	public DelayedNode(AsyncTask task,long delay,long baseTime) {
		assert delay >0;
		this.deadlineNanos =Math.max(0,baseTime-START_TIME + delay);
		this.task = task;
	}
	public DelayedNode(AsyncTask task,long delay) {
		this(task,delay,System.nanoTime());
	}
	public DelayedNode(AsyncTask task,long delay,long baseTime,TimeUnit unit) {
		this(task,unit.toNanos(delay),unit.toNanos(baseTime));
	}
	public DelayedNode(AsyncTask task,long delay,TimeUnit unit) {
		this(task,unit.toNanos(delay),System.nanoTime());
	}
	
	AsyncTask getTask(){
		return this.task;
	}
	
	public long getDeadlineNanos() {
		return deadlineNanos;
	}
	public long getDelay(TimeUnit unit) {
		return unit.convert(Math.max(0, deadlineNanos - System.nanoTime()+START_TIME), TimeUnit.NANOSECONDS);
	}

	public int compareTo(Delayed o) {
		if (this == o) {
			return 0;
		}

		DelayedNode that = (DelayedNode) o;
		long d = this.deadlineNanos - that.deadlineNanos;
		if (d < 0) {
			return -1;
		} else if (d > 0) {
			return 1;
		} else if (id < that.id) {
			return -1;
		} else if (id == that.id) {
			throw new Error();
		} else {
			return 1;
		}
	}

}
