package org.jfw.jina.util.concurrent;


public interface Promise<V> {

	public static int STATE_INIT = 1;
	public static int STATE_UNCANCELLABLE = 2;
	public static int STATE_SUCCESS = 4;
	public static int STATE_FAIL = 8;
	public static int STATE_CANCELED = 16;
	
	
	int state();

	void cancel();

	boolean isCancelled();
	
	boolean isSuccess();


	V get() ;

	Throwable cause();	
	
	void addListener(final PromiseListener<Promise<V>> listener);
	void addListener(final PromiseListener<Promise<V>>... listeners);

}
