package org.jfw.jina.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


public abstract class AbstractEventExecutorGroup implements EventExecutorGroup {
	private final EventExecutor[] children;
	private final Set<EventExecutor> readonlyChildren;
	private final AtomicInteger terminatedChildren = new AtomicInteger();
	private final CountDownLatch downLatch = new CountDownLatch(1);
	private final EventExecutorChooser chooser;
	private final AtomicInteger idx = new AtomicInteger();
	private final int idxPapam;
	
	
	


	protected AbstractEventExecutorGroup(final int nThreads) {
		children = this.buildChild(nThreads);
		Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
		Collections.addAll(childrenSet, children);
		readonlyChildren = Collections.unmodifiableSet(childrenSet);
		boolean isPowerOfTwo = (nThreads & -nThreads) == nThreads;
		if (isPowerOfTwo) {
			idxPapam = nThreads - 1;
		} else {
			idxPapam = nThreads;
		}
		this.chooser = this.buildChooser(isPowerOfTwo);

	}

	private EventExecutorChooser buildChooser(boolean isPowerOfTwo) {
		return isPowerOfTwo ? new EventExecutorChooser() {
			public EventExecutor next() {
				return children[idx.getAndIncrement() & idxPapam];
			}
		} : new EventExecutorChooser() {
			public EventExecutor next() {
				return children[Math.abs(idx.getAndIncrement() % idxPapam)];
			}
		};
	}

	private EventExecutor[] buildChild(int nThreads) {
		assert nThreads > 0;
		EventExecutor[] children = new EventExecutor[nThreads];
		for (int i = 0; i < nThreads; i++) {
			boolean success = false;
			try {
				children[i] = newChild();
				success = true;
			} catch (Exception e) {
				throw new IllegalStateException("failed to create a child event loop", e);
			} finally {
				if (!success) {
					for (int j = 0; j < i; j++) {
						children[j].shutdown();
						children[j] = null;
					}
				}
			}
		}
		PromiseListener<Promise<Object>> listener = new PromiseListener<Promise<Object>>() {

			public void complete(Promise<Object> future) throws Exception {
				if (future.isSuccess()) {
					if (terminatedChildren.decrementAndGet() == 0) {
						downLatch.countDown();
					}
				}

			}
		};
		terminatedChildren.set(nThreads);
		for (EventExecutor e : children)
			e.terminationPromise().addListener(listener);
		return children;

	}

	public Iterator<EventExecutor> iterator() {
		return readonlyChildren.iterator();
	}

	public EventExecutor next() {
		return this.chooser.next();
	}

	public void shutdown() {
		for (EventExecutor e : this.children) {
			e.shutdown();
		}
	}

	public void waitShutdown() {
		
		for(EventExecutor e:this.children){
			if(e.inEventLoop()) throw new IllegalAccessError("deadLock");
		}
		for (;;) {
			try {
				 this.downLatch.await();
				 return;
			} catch (InterruptedException e) {
			}
		}

	}

	protected abstract EventExecutor newChild();

	interface EventExecutorChooser {
		EventExecutor next();
	}

	public static void main(String[] args) {
		for (int val = 1; val < 100; ++val)

			System.out.println("" + val + ":" + ((val & -val) == val));

	}
}
