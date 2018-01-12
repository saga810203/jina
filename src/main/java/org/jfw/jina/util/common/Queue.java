package org.jfw.jina.util.common;

public interface Queue {

	void clear();
	Node add(Object item,Object tag);
	Node add(Object item);
	Node before(Node node,Object item,Object tag);
	Node after(Node node,Object item,Object tag);
	void remove(Node node);
	void remove(Node begin,Node end);
	void process(Handler h);
	void removeAndFree(Node node);
	void removeAndFree(Node begin,Node end);
	void processAndFree(Handler h);
	
	void moveTo(Queue queue);
	void moveTo(Queue queue,Matcher m);
	boolean isEmpty();
	Node first();
	Object firstValue();
	Object firstTag();

	
	
	
	public interface Node{}
	public interface Handler{
		void begin(Queue queue);
		boolean handle(Queue queue,Node node,Object item,Object tag);
		void end(Queue queue);
	}
	public interface Matcher{
		boolean match(Queue queue,Node node,Object item,Object tag);
	}
}
