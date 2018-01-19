package org.jfw.jina.core;

import java.util.concurrent.TimeUnit;

import org.jfw.jina.util.QueueProvider;
import org.jfw.jina.util.concurrent.AsyncExecutorGroup;


public interface AsyncExecutor extends QueueProvider {
	AsyncExecutorGroup group();
    boolean inLoop();
    void shutdown();
    void submit(AsyncTask task);
    void schedule(AsyncTask task, long delay, TimeUnit unit);
    <T> T get(int idx);
    int set(Object val);
}
