package org.jfw.jina.core;

public interface AsyncExecutorGroup extends Iterable<AsyncExecutor>{
	AsyncExecutor next();
	void shutdown();
	void waitShutdown(boolean sendDirective);
	Object getParameter(Object key);
}
