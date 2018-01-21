package org.jfw.jina.util;

import java.util.Comparator;

public interface TagQueue extends Queue{
	void clear(TagQueueHandler handler);
	void clear(TagQueueMatcher matcher);
	TagNode offer(Object item,Object tag);
    Object peekTag();
    TagNode peekTagNode();
    void removeWithTag(Matcher<Object> matcher);
    void offerToWithTag(Queue dest,Matcher<Object> matcher);	
    void offerToTagQueue(TagQueue dest);
    void offerToTagQueue(TagQueue dest,Matcher<Object> matcher);
    void offerToTagQueueWithTag(TagQueue dest,Matcher<Object> matcher);
    
    void beforeWithTag(Object item,Object tag,Comparator<Object> comparator);
    
    public interface TagNode extends Node{
    	Object tag();
    } 
    public interface TagQueueHandler{
    	void process(Object item,Object tag);
    }
    public interface TagQueueMatcher{
    	boolean match(Object item,Object tag);
    }
}
