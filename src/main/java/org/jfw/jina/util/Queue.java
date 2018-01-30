package org.jfw.jina.util;

public interface Queue<I> {
	boolean isEmpty();
	void clear(Handler<? super I> handler);
	void clear(Matcher<? super I> matcher);
	void free(Handler<? super I> handler);
	I find(Matcher<? super I> matcher);
	Node offer(I item);
    I peek();
    I poll();

    
  
    //return last (matcher.match()==true)
    void remove(Matcher<I> matcher);
    void offerTo(Queue<I> dest,Matcher<I> matcher);	
    void offerTo(Queue<I> dest);
    
    void unsafeShift();
    I unsafePeek();
    I unsafePeekLast();
    
    
	public interface Node{
		<I> I  item();
	}
}
