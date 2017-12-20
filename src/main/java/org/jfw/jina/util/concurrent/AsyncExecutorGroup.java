package org.jfw.jina.util.concurrent;

public interface AsyncExecutorGroup extends Iterable<AsyncExecutor>{
	AsyncExecutor next();
	void shutdown();
	void waitShutdown(boolean sendDirective);	
}
