package org.jfw.jina.util;

public interface Queue {
	void clear();
	void free();
	void enqueue(Node node);
	Node offer(Object item);
    Object peek();
    Object poll();
    //return last (matcher.match()==true)
    Node remove(Matcher matcher);
    Node OfferTo(Queue dest,Matcher matcher);	
	public interface Node{
		Object item();
	}
}
