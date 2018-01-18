package org.jfw.jina.util;

public interface TagQueue extends Queue{
	void enqueue(Node node);
	Node offer(Object item,Object tag);
    Object peekTag();
    Node OfferToTagQueue(TagQueue dest,Matcher matcher);	
}
