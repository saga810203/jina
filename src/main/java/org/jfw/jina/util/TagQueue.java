package org.jfw.jina.util;

public interface TagQueue extends Queue{
	Node offer(Object item,Object tag);
    Object peekTag();
    void removeWithTag(Matcher<Object> matcher);
    void OfferToWithTag(Queue dest,Matcher<Object> matcher);	
    void OfferToTagQueue(TagQueue dest);
    void OfferToTagQueue(TagQueue dest,Matcher<Object> matcher);
    void OfferToTagQueueWithTag(TagQueue dest,Matcher<Object> matcher);
    
    public interface TagNode extends Node{
    	Object tag();
    }    
}
