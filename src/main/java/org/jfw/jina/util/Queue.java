package org.jfw.jina.util;

public interface Queue {
	boolean isEmpty();
	void clear(Handler handler);
	void clear(Matcher<Object> matcher);
	void free(Handler handler);
	Node offer(Object item);
    Object peek();
    Object poll();

    
  
    //return last (matcher.match()==true)
    void remove(Matcher<Object> matcher);
    void offerTo(Queue dest,Matcher<Object> matcher);	
    void offerTo(Queue dest);
    
    void unsafeShift();
    Object unsafePeek();
    Object unsafePeekLast();
    
    
	public interface Node{
		Object item();
	}
}
