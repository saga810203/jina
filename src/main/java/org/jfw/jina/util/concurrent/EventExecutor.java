package org.jfw.jina.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;


public interface EventExecutor {

	EventExecutorGroup group();

    <V> Promise<V> newPromise();

   
    <V> Promise<V> newSucceededPromise(V result);


    <V> Promise<V> newFailedPromise(Throwable cause);
    boolean inEventLoop();

    boolean isShuttingDown();
    boolean isShutdown();

    void shutdown();
    
    Promise<?> terminationPromise();

    Promise<?> submit(Runnable task);


    <T> Promise<T> submit(Runnable task, T result);

    <T> Promise<T> submit(Callable<T> task);

    ScheduledPromise<?> schedule(Runnable command, long delay, TimeUnit unit);
  
    <V> ScheduledPromise<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    ScheduledPromise<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    ScheduledPromise<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
