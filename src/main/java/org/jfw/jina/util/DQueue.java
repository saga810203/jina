package org.jfw.jina.util;

public interface DQueue<I> extends Queue<I>{
	@Override
    DNode offer(I item);
    void offerToDQueue(DQueue<I> dest,Matcher<I> matcher);
    void offerToDQueue(DQueue<I> dest);
	public interface DNode extends Node{
		void dequeue();
		<I> void enqueue(DQueue<I> dqueue);
		boolean inQueue();
	}
}
