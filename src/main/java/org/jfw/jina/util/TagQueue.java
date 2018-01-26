package org.jfw.jina.util;

import java.util.Comparator;

public interface TagQueue<I,T> extends Queue<I>{
	void clear(TagQueueHandler<I,T> handler);
	void clear(TagQueueMatcher<I,T> matcher);
	TagNode offer(I item,T tag);
    Object peekTag();
	Object unsafePeekTag();
    TagNode peekTagNode();
    void removeWithTag(Matcher<T> matcher);
    void offerToWithTag(Queue<I> dest,Matcher<T> matcher);	
    void offerToTagQueue(TagQueue<I,T> dest);
    void offerToTagQueue(TagQueue<I,T> dest,Matcher<I> matcher);
    void offerToTagQueueWithTag(TagQueue<I,T> dest,Matcher<T> matcher);
    
    void beforeWithTag(I item,T tag,Comparator<T> comparator);
    void beforeWith(I item,T tag,Comparator<I> comparator);
    
    public interface TagNode extends Node{
    <T>	T tag();
    } 
    public interface TagQueueHandler<I,T>{
    	void process(I item,T tag);
    }
    public interface TagQueueMatcher<I,T>{
    	boolean match(I item,T tag);
    }

}
