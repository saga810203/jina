package org.jfw.jina.util;

import org.jfw.jina.util.DQueue.DNode;

public interface QueueProvider {
	DNode newDNode(Object item);
	void freeDNode(DNode node);
	Queue newQueue();
	DQueue newDQueue();
	TagQueue newTagQueue();
}
