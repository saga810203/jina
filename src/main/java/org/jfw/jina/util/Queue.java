package org.jfw.jina.util;

public interface Queue {
	boolean isEmpty();
	void clear();
	void free();
	Node offer(Object item);
    Object peek();
    Object poll();
    //return last (matcher.match()==true)
    void remove(Matcher<Object> matcher);
    void OfferTo(Queue dest,Matcher<Object> matcher);	
    void OfferTo(Queue dest);
	public interface Node{
		Object item();
	}
}
