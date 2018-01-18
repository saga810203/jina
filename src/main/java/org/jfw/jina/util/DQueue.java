package org.jfw.jina.util;

public interface DQueue extends Queue{
	DNode offer(Object item);
    //return last (matcher.match()==true)
    DNode remove(Matcher matcher);
    DNode OfferTo(DQueue dest,Matcher matcher);	
	public interface DNode extends Node{
		void dequeue();
	}
}
