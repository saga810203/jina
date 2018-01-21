package org.jfw.jina.core;

import java.util.concurrent.TimeUnit;


public interface AsyncExecutor {
	AsyncExecutorGroup group();
    boolean inLoop();
    void shutdown();
    void submit(AsyncTask task);
    void schedule(AsyncTask task, long delay, TimeUnit unit);
    <T> T get(int idx);
    int set(Object val);
}
