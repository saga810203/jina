package org.jfw.jina.util.concurrent;

import org.jfw.jina.core.AsyncExecutor;

public interface AsyncExecutorGroup extends Iterable<AsyncExecutor>{
	AsyncExecutor next();
	void shutdown();
	void waitShutdown(boolean sendDirective);	
}
