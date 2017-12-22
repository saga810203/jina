package org.jfw.jina.util.concurrent;

import java.util.concurrent.TimeUnit;


public interface AsyncExecutor {
	AsyncExecutorGroup group();
    boolean inLoop();
    void shutdown();
    void submit(AsyncTask task);
    void schedule(AsyncTask task, long delay, TimeUnit unit);
    <T> T getObject(Object key);
}
