package org.jfw.jina.util;

public interface DQueue extends Queue{
	@Override
	DNode offer(Object item);
    void offerToDQueue(DQueue dest,Matcher<Object> matcher);
    void offerToDQueue(DQueue dest);
	public interface DNode extends Node{
		void dequeue();
		void enqueue(DQueue dqueue);
	}
}
