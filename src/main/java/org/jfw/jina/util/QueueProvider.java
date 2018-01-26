package org.jfw.jina.util;

import org.jfw.jina.util.DQueue.DNode;

public interface QueueProvider {
	<I>	DNode   newDNode(I item);
	<I> void freeDNode(DNode  node);
	<I> Queue<I> newQueue();
	<I> DQueue<I> newDQueue();
	<I,T> TagQueue<I,T> newTagQueue();
}
