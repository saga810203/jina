package org.jfw.jina.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.impl.ProxyAsyncExecutor;

public abstract class AbstractAsyncExecutorGroup implements AsyncExecutorGroup {
	private final AsyncExecutor[] children;
	private final Set<AsyncExecutor> readonlyChildren;
	private final AtomicInteger terminatedChildren = new AtomicInteger();
	private final CountDownLatch downLatch;
	private final EventExecutorChooser chooser;
	private final AtomicInteger idx = new AtomicInteger();
	private final int idxPapam;

	protected final Runnable childCloseTask = new Runnable() {
		public void run() {
			if (terminatedChildren.decrementAndGet() == 0) {
				downLatch.countDown();
			}
		}
	};

	protected AbstractAsyncExecutorGroup(final int nThreads) {
		children = this.buildChild(nThreads);
		this.terminatedChildren.set(nThreads);
		this.downLatch = new CountDownLatch(1);

		Set<AsyncExecutor> childrenSet = new LinkedHashSet<AsyncExecutor>(children.length);
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
			public AsyncExecutor next() {
				return children[idx.getAndIncrement() & idxPapam];
			}
		} : new EventExecutorChooser() {
			public AsyncExecutor next() {
				return children[Math.abs(idx.getAndIncrement() % idxPapam)];
			}
		};
	}

	private AsyncExecutor[] buildChild(int nThreads) {
		assert nThreads > 0;
		AsyncExecutor[] children = new AsyncExecutor[nThreads];
		for (int i = 0; i < nThreads; i++) {
			boolean success = false;
			try {
				children[i] = new ProxyAsyncExecutor(children, i,this);
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
		return children;
	}

	public Iterator<AsyncExecutor> iterator() {
		return readonlyChildren.iterator();
	}

	public AsyncExecutor next() {
		return this.chooser.next();
	}

	public void shutdown() {
		for (AsyncExecutor e : this.children) {
			e.shutdown();
		}
	}

	public void waitShutdown(boolean sendDirective) {
		if (sendDirective)
			this.shutdown();
		for (AsyncExecutor e : this.children) {
			if (e.inLoop())
				throw new IllegalAccessError("deadLock");
		}
		for (;;) {
			try {
				this.downLatch.await();
				return;
			} catch (InterruptedException e) {
			}
		}
	}

	public abstract AsyncExecutor newChild(int idx);

	interface EventExecutorChooser {
		AsyncExecutor next();
	}

	public static void main(String[] args) {
		for (int val = 1; val < 100; ++val)

			System.out.println("" + val + ":" + ((val & -val) == val));

	}
}
