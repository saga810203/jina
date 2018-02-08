package org.jfw.jina.util.impl;

import java.util.Comparator;

import org.jfw.jina.util.DQueue;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.Matcher;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.QueueProvider;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.concurrent.SystemPropertyUtil;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class QueueProviderImpl implements QueueProvider {

	private static final int MAX_NUM_POOLED_NODE = SystemPropertyUtil.getInt("org.jfw.jina.util.QueueProvider.MAX_NUM_POOLED_NODE", 1024 * 1024);
	private int numNode = 0;
	private LinkedNode headOfProvider = new LinkedNode(null);

	public void freeDNode(DNode node) {
		assert node != null;
		assert node instanceof LinkedNode;
		if (numNode < MAX_NUM_POOLED_NODE) {
			++numNode;
			((LinkedNode) node).tag = null;
			((LinkedNode) node).next = headOfProvider.next;
			headOfProvider.next = ((LinkedNode) node);
		}
	}

	public void freeNode(LinkedNode node) {
		assert node != null;
		assert node instanceof LinkedNode;
		if (numNode < MAX_NUM_POOLED_NODE) {
			++numNode;
			node.next = headOfProvider.next;
			headOfProvider.next = ((LinkedNode) node);
		}
	}

	public void freeNode(LinkedNode begin, LinkedNode end, int num) {
		assert begin != null;
		assert end != null;
		assert num > 1;
		assert count(begin, end) == num;
		if (numNode < MAX_NUM_POOLED_NODE) {
			numNode += num;
			end.next = headOfProvider.next;
			headOfProvider.next = begin;
		}
	}

	public <I> LinkedNode newDNode(I item) {
		LinkedNode ret = headOfProvider.next;
		if (ret != null) {
			--numNode;
			headOfProvider.next = ret.next;
			ret.next = null;
			ret.item = item;
			return ret;
		}
		ret = new LinkedNode(item);
		return ret;
	}

	public <I, T> LinkedNode newTagNode(I item, T tag) {
		LinkedNode ret = headOfProvider.next;
		if (ret != null) {
			--numNode;
			headOfProvider.next = ret.next;
			ret.next = null;
			ret.item = item;
			ret.tag = tag;
			return ret;
		}
		ret = new LinkedNode(item, tag);
		return ret;
	}

	@Override
	public <I> Queue<I> newQueue() {
		return new LinkedQueue<I>();
	}

	@Override
	public <I> DQueue<I> newDQueue() {
		return new DeLinkedQueue<I>();
	}

	@Override
	public <I, T> TagQueue<I, T> newTagQueue() {
		return new TagLinkedQueue<I, T>();
	}

	public class LinkedQueue<I> implements Queue<I> {
		public LinkedNode head;

		private LinkedQueue() {
			head = newDNode(null);
			head.tag = head;
		}

		@Override
		public void clear(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 0;
			while (node != null) {
				++i;
				handler.process((I) node.item);
				node.item = null;
				node = node.next;
			}
			if (i > 1) {
				freeNode(head.next, (LinkedNode) head.tag, i);
			} else if (i == 1) {
				freeNode(head.next);
			} else {
				return;
			}
			head.next = null;
			head.tag = head;
		}

		@Override
		public void free(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 1;
			while (node != null) {
				handler.process((I) node.item);
				node.item = null;
				++i;
				node = node.next;
			}
			if (i > 1) {
				LinkedNode end = (LinkedNode) head.tag;
				head.tag = null;
				freeNode(head, end, i);
			} else {
				head.tag = null;
				freeNode(head);
			}

			head = null;
		}

		@Override
		public LinkedNode offer(I item) {
			LinkedNode node = newDNode(item);
			LinkedNode last = (LinkedNode) head.tag;
			last.next = node;
			head.tag = node;
			return node;
		}

		@Override
		public I peek() {
			LinkedNode node = head.next;
			return (I) (node != null ? node.item : null);
		}

		@Override
		public I poll() {
			Object ret = null;
			LinkedNode node = head.next;
			if (node == null) {
				return null;
			} else if (node == head.tag) {
				head.next = null;
				head.tag = head;
			} else {
				head.next = node.next;
			}
			ret = node.item;
			node.item = null;
			freeNode(node);
			return (I) ret;
		}

		@Override
		public void unsafeShift() {
			assert head.next != null;
			LinkedNode node = head.next;
			head.next = node.next;
			node.item = null;
			if (node.next == null) {
				head.tag = head;
			}
			freeNode(node);
		}

		public I unsafePeek() {
			assert head.next != null;
			return (I) head.next.item;
		}

		public I unsafePeekLast() {
			assert head.tag != null;
			return (I) ((LinkedNode) head.tag).item;
		}

		@Override
		public I find(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode node = head.next;
			for (;;) {
				if (node == null)
					return null;
				I ret = (I) node.item;
				if (matcher.match(ret)) {
					return ret;
				}
				node = node.next;
			}
		}

		@Override
		public void remove(Matcher<I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode node = begin;
				LinkedNode end = null;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					node.item = null;
					end = node;
					node = node.next;
					++i;
				}
				if (i > 1) {
					// node = end.next;
					freeNode(head.next, end, i);
				} else if (i == 1) {
					// node = end.next;
					freeNode(end);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public void offerTo(Queue<I> dest, Matcher<I> matcher) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					end = node;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue<I>) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public boolean isEmpty() {
			return head.next != null;
		}

		@Override
		public void offerTo(Queue<I> dest) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			if (head.next != null) {
				LinkedNode destHead = (LinkedNode) ((LinkedQueue<I>) dest).head;
				LinkedNode destLast = (LinkedNode) ((LinkedQueue<I>) dest).head.tag;
				destLast.next = head.next;
				destHead.tag = head.tag;
				head.next = null;
				head.tag = head;
			}
		}

		@Override
		public void clear(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					node.item = null;
					end = node;
					node = node.next;
				}
				if (i > 1) {
					// node = end.next;
					// end.next = null;
					freeNode(begin, end, i);
				} else if (i == 1) {
					freeNode(begin);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}
	}

	public class DeLinkedQueue<I> implements DQueue<I> {
		public LinkedNode head;

		private DeLinkedQueue() {
			head = newDNode(null);
			head.tag = head;
		}

		@Override
		public I find(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode node = head.next;
			for (;;) {
				if (node == null)
					return null;
				I ret = (I) node.item;
				if (matcher.match(ret)) {
					return ret;
				}
				node = node.next;
			}
		}

		@Override
		public void clear(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 0;
			while (node != null) {
				++i;
				handler.process((I) node.item);
				node.item = null;
				node.tag = null;
				node = node.next;
			}
			if (i > 1) {
				freeNode(head.next, (LinkedNode) head.tag, i);
			} else if (i == 1) {
				freeNode(head.next);
			} else {
				return;
			}
			head.next = null;
			head.tag = head;
		}

		@Override
		public void clear(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
				}
				if (i > 1) {
					// node = end.next;
					// end.next = null;
					freeNode(begin, end, i);
				} else if (i == 1) {
					freeNode(begin);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public void free(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 1;
			while (node != null) {
				handler.process((I) node.item);
				node.item = null;
				node.tag = null;
				++i;
				node = node.next;
			}
			if (i > 1) {
				LinkedNode end = (LinkedNode) head.tag;
				head.tag = null;
				freeNode(head, end, i);
			} else {
				head.tag = null;
				freeNode(head);
			}
			head = null;
		}

		@Override
		public LinkedNode offer(Object item) {
			LinkedNode node = newDNode(item);
			LinkedNode last = (LinkedNode) head.tag;
			last.next = node;
			node.tag = last;
			head.tag = node;
			return node;
		}

		@Override
		public I peek() {
			LinkedNode node = head.next;
			return (I) (node != null ? node.item : null);
		}

		@Override
		public I unsafePeek() {
			assert head.next != null;
			return (I) head.next.item;
		}

		public I unsafePeekLast() {
			assert head.tag != null;
			return (I) ((LinkedNode) head.tag).item;
		}

		@Override
		public I poll() {
			Object ret = null;
			LinkedNode node = head.next;
			if (node == null) {
				return null;
			} else if (node == head.tag) {
				head.next = null;
				head.tag = head;
			} else {
				LinkedNode Next = node.next;
				head.next = Next;
				Next.tag = head;
			}
			ret = node.item;
			node.tag = null;
			node.item = null;
			freeNode(node);
			return (I) ret;
		}

		@Override
		public void unsafeShift() {
			assert head.next != null;
			LinkedNode node = head.next;
			head.next = node.next;
			node.item = null;
			node.tag = null;
			if (node.next == null) {
				head.tag = head;
			}
			freeNode(node);
		}

		@Override
		public void remove(Matcher<I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode node = begin;
				LinkedNode end = null;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
					++i;
				}
				if (i > 1) {
					// node = end.next;
					freeNode(head.next, end, i);
				} else if (i == 1) {
					// node = end.next;
					freeNode(end);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				} else {
					node.tag = head;
				}
			}
		}

		@Override
		public void offerTo(Queue<I> dest, Matcher<I> matcher) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					end = node;
					node.tag = null;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue<I>) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				} else {
					node.tag = head;
				}
			}
		}

		@Override
		public boolean isEmpty() {
			return head.next != null;
		}

		@Override
		public void offerToDQueue(DQueue<I> dest, Matcher<I> matcher) {
			assert dest != null;
			assert dest instanceof DeLinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					end = node;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((DeLinkedQueue) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					begin.tag = destLast;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				} else {
					node.tag = head;
				}
			}

		}

		@Override
		public void offerTo(Queue<I> dest) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			if (head.next != null) {
				LinkedNode destHead = head.next;
				while (destHead != null) {
					destHead.tag = null;
				}
				destHead = (LinkedNode) ((LinkedQueue) dest).head;
				LinkedNode destLast = (LinkedNode) ((LinkedQueue) dest).head.tag;
				destLast.next = head.next;
				destHead.tag = head.tag;
				head.next = null;
				head.tag = head;
			}

		}

		@Override
		public void offerToDQueue(DQueue<I> dest) {
			assert dest != null;
			assert dest instanceof DeLinkedQueue;
			if (head.next != null) {
				LinkedNode destHead = (LinkedNode) ((DeLinkedQueue) dest).head;
				LinkedNode destLast = (LinkedNode) ((DeLinkedQueue) dest).head.tag;
				destLast.next = head.next;
				head.next.tag = destLast;
				destHead.tag = head.tag;
				head.next = null;
				head.tag = head;
			}

		}

	}

	public class TagLinkedQueue<I, T> implements TagQueue<I, T> {
		public LinkedNode head;

		private TagLinkedQueue() {
			head = newDNode(null);
			head.tag = head;
		}

		@Override
		public I find(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode node = head.next;
			for (;;) {
				if (node == null)
					return null;
				I ret = (I) node.item;
				if (matcher.match(ret)) {
					return ret;
				}
				node = node.next;
			}
		}

		@Override
		public void clear(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 0;
			while (node != null) {
				++i;
				handler.process((I) node.item);
				node.tag = null;
				node.item = null;
				node = node.next;
			}
			if (i > 1) {
				freeNode(head.next, (LinkedNode) head.tag, i);
			} else if (i == 1) {
				freeNode(head.next);
			} else {
				return;
			}
			head.next = null;
			head.tag = head;
		}

		@Override
		public void clear(TagQueueHandler<? super I, T> handler) {
			LinkedNode node = head.next;
			int i = 0;
			while (node != null) {
				++i;
				handler.process((I) node.item, (T) node.tag);
				node.tag = null;
				node.item = null;
				node = node.next;
			}
			if (i > 1) {
				freeNode(head.next, (LinkedNode) head.tag, i);
			} else if (i == 1) {
				freeNode(head.next);
			} else {
				return;
			}
			head.next = null;
			head.tag = head;
		};

		@Override
		public void clear(Matcher<? super I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
				}
				if (i > 1) {
					// node = end.next;
					// end.next = null;
					freeNode(begin, end, i);
				} else if (i == 1) {
					freeNode(begin);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public void clear(TagQueueMatcher<? super I, T> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item, (T) node.tag)) {
					++i;
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
				}
				if (i > 1) {
					// node = end.next;
					// end.next = null;
					freeNode(begin, end, i);
				} else if (i == 1) {
					freeNode(begin);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		};

		@Override
		public void free(Handler<? super I> handler) {
			LinkedNode node = head.next;
			int i = 1;
			while (node != null) {
				handler.process((I) node.item);
				node.item = null;
				node.tag = null;
				++i;
				node = node.next;
			}
			if (i > 1) {
				LinkedNode end = (LinkedNode) head.tag;
				head.tag = null;
				freeNode(head, end, i);
			} else {
				head.tag = null;
				freeNode(head);
			}

			head = null;
		}

		@Override
		public LinkedNode offer(Object item) {
			throw new UnsupportedOperationException();
		}

		@Override
		public I peek() {
			LinkedNode node = head.next;
			return (I) (node != null ? node.item : null);
		}

		@Override
		public I unsafePeek() {
			assert head.next != null;
			return (I) head.next.item;
		}

		public I unsafePeekLast() {
			assert head.tag != null;
			return (I) ((LinkedNode) head.tag).item;
		}

		@Override
		public Object unsafePeekTag() {
			assert head.next != null;
			return head.next.tag;
		}

		@Override
		public I poll() {
			Object ret = null;
			LinkedNode node = head.next;
			if (node == null) {
				return null;
			} else if (node == head.tag) {
				head.next = null;
				head.tag = head;
			} else {
				head.next = node.next;
			}
			ret = node.item;
			node.item = null;
			node.tag = null;
			freeNode(node);
			return (I) ret;
		}

		@Override
		public void unsafeShift() {
			assert head.next != null;
			LinkedNode node = head.next;
			head.next = node.next;
			node.item = null;
			node.tag = null;
			if (node.next == null) {
				head.tag = head;
			}
			freeNode(node);
		}

		@Override
		public void remove(Matcher<I> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode node = begin;
				LinkedNode end = null;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
					++i;
				}
				if (i > 1) {
					// node = end.next;
					freeNode(head.next, end, i);
				} else if (i == 1) {
					// node = end.next;
					freeNode(end);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public void offerTo(Queue<I> dest, Matcher<I> matcher) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					end = node;
					node.tag = null;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}
		}

		@Override
		public boolean isEmpty() {
			return head.next != null;
		}

		@Override
		public void offerTo(Queue<I> dest) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			if (head.next != null) {
				LinkedNode destHead = head.next;
				while (destHead != null) {
					destHead.tag = null;
				}
				destHead = (LinkedNode) ((LinkedQueue) dest).head;
				LinkedNode destLast = (LinkedNode) ((LinkedQueue) dest).head.tag;
				destLast.next = head.next;
				destHead.tag = head.tag;
				head.next = null;
				head.tag = head;
			}
		}

		@Override
		public TagNode offer(Object item, Object tag) {
			LinkedNode node = newDNode(item);
			node.tag = tag;
			LinkedNode last = ((LinkedNode) head.tag);
			last.next = node;
			head.tag = node;
			return (TagNode) node;
		}

		@Override
		public Object peekTag() {
			LinkedNode node = head.next;
			return node == null ? null : node.tag;
		}

		@Override
		public TagNode peekTagNode() {
			return (TagNode) head.next;
		}

		@Override
		public void removeWithTag(Matcher<T> matcher) {
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode node = begin;
				LinkedNode end = null;
				int i = 0;
				while ((null != node) && matcher.match((T) node.tag)) {
					node.item = null;
					node.tag = null;
					end = node;
					node = node.next;
					++i;
				}
				if (i > 1) {
					// node = end.next;
					freeNode(head.next, end, i);
				} else if (i == 1) {
					// node = end.next;
					freeNode(end);
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}

		}

		@Override
		public void offerToWithTag(Queue<I> dest, Matcher<T> matcher) {
			assert dest != null;
			assert dest instanceof LinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((T) node.tag)) {
					++i;
					end = node;
					node.tag = null;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}

		}

		@Override
		public void offerToTagQueue(TagQueue<I, T> dest) {
			assert dest != null;
			assert dest instanceof TagLinkedQueue;
			if (head.next != null) {
				LinkedNode destHead = (LinkedNode) ((TagLinkedQueue) dest).head;
				LinkedNode destLast = (LinkedNode) ((TagLinkedQueue) dest).head.tag;
				destLast.next = head.next;
				destHead.tag = head.tag;
				head.next = null;
				head.tag = head;
			}

		}

		@Override
		public void offerToTagQueue(TagQueue<I, T> dest, Matcher<I> matcher) {
			assert dest != null;
			assert dest instanceof TagLinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((I) node.item)) {
					++i;
					end = node;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}

		}

		@Override
		public void offerToTagQueueWithTag(TagQueue<I, T> dest, Matcher<T> matcher) {
			assert dest != null;
			assert dest instanceof TagLinkedQueue;
			assert matcher != null;
			LinkedNode begin = head.next;
			if (begin != null) {
				LinkedNode end = null;
				LinkedNode node = begin;
				int i = 0;
				while ((null != node) && matcher.match((T) node.tag)) {
					++i;
					end = node;
					node = node.next;
				}
				if (i > 0) {
					// node = end.next;
					// end.next = null;
					LinkedNode destHead = ((LinkedQueue) dest).head;
					LinkedNode destLast = (LinkedNode) destHead.tag;
					destLast.next = begin;
					destHead.tag = end;
					end.next = null;
				} else {
					return;
				}
				head.next = node;
				if (node == null) {
					head.tag = head;
				}
			}

		}

		@Override
		public void beforeWithTag(I item, T tag, Comparator<T> comparator) {
			LinkedNode target = newDNode(item);
			target.tag = tag;
			LinkedNode node = head.next;
			LinkedNode prev = head;
			while (node != null) {
				if (comparator.compare((T) node.tag, (T) tag) > 0) {
					target.next = node;
					prev.next = target;
					return;
				}
				prev = node;
				node = prev.next;
			}
			prev.next = target;
			head.tag = target;
		}

		@Override
		public void beforeWith(I item, T tag, Comparator<I> comparator) {
			LinkedNode target = newDNode(item);
			target.tag = tag;
			LinkedNode node = head.next;
			LinkedNode prev = head;
			while (node != null) {
				if (comparator.compare((I) node.item, (I) item) > 0) {
					target.next = node;
					prev.next = target;
					return;
				}
				prev = node;
				node = prev.next;
			}
			prev.next = target;
			head.tag = target;
		}

	}

	public static class LinkedNode implements DNode, TagNode {
		public Object item;
		public LinkedNode next;
		public Object tag;

		private <I> LinkedNode(I item) {
			this.item = item;
		}

		private <I, T> LinkedNode(I item, T tag) {
			this.item = item;
			this.tag = tag;
		}

		@Override
		public <I> I item() {
			return (I) item;
		}

		@Override
		public <T> T tag() {
			return (T) tag;
		}

		@Override
		public void dequeue() {
			((LinkedNode) tag).next = next;
			if (next != null) {
				next.tag = tag;
			}
			this.tag = null;
			this.next = null;
		}
		@Override
		public boolean inQueue() {
			return this.tag!=null;
		}

		@Override
		public void enqueue(DQueue dest) {
			assert dest != null;
			assert dest instanceof DeLinkedQueue;
			LinkedNode destHead = ((DeLinkedQueue) dest).head;
			LinkedNode destLast = (LinkedNode) destHead.tag;
			destLast.next = this;
			this.tag = destLast;
			destHead.tag = this;
		}

	}

	private static int count(LinkedNode begin, LinkedNode end) {
		int ret = 1;
		LinkedNode node = begin;
		while (node != end) {
			++ret;
			node = node.next;
		}
		return ret;
	}

}
