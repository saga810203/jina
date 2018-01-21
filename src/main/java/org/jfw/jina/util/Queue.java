package org.jfw.jina.util;

public interface Queue {
	boolean isEmpty();
	void clear(Handler handler);
	void clear(Matcher<Object> matcher);
	void free(Handler handler);
	Node offer(Object item);
    Object peek();
    Object poll();
    void shift();
    //return last (matcher.match()==true)
    void remove(Matcher<Object> matcher);
    void offerTo(Queue dest,Matcher<Object> matcher);	
    void offerTo(Queue dest);
	public interface Node{
		Object item();
	}
}
