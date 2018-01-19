package org.jfw.jina.core;

public interface TaskCompletionHandler {
	void completed(AsyncExecutor executor);
	void failed(Throwable exc,AsyncExecutor executor); 
}
