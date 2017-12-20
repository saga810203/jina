package org.jfw.jina.util.concurrent;

public interface AsyncTask{
	void execute(AsyncExecutor executor) throws Throwable;
	void completed(AsyncExecutor executor);
	void failed(Throwable exc,AsyncExecutor executor); 
	void cancled(AsyncExecutor executor);
}
