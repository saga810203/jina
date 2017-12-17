package org.jfw.jina.util.concurrent;

public interface EventExecutorGroup extends Iterable<EventExecutor>{
	EventExecutor next();
	void shutdown();
	void waitShutdown();
	
}
