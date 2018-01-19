package org.jfw.jina.core;

public interface AsyncTask extends TaskCompletionHandler{
	void execute(AsyncExecutor executor) throws Throwable;
	void cancled(AsyncExecutor executor);
}
