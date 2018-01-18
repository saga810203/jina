package org.jfw.jina.util;

public interface TagDQueue extends TagQueue{
	
	Node OfferToTagDQueue(TagDQueue dest,Matcher matcher);	
	
	public interface TagDNode extends TagNode{	
	}
}
