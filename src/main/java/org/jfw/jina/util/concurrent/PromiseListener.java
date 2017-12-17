package org.jfw.jina.util.concurrent;


public interface PromiseListener<F extends Promise<?>> {
	void complete(F future);
}
