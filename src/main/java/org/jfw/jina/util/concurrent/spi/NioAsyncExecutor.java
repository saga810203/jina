package org.jfw.jina.util.concurrent.spi;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jfw.jina.util.ReflectionUtil;
import org.jfw.jina.util.concurrent.AsyncChannel;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncExecutorGroup;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.SystemPropertyUtil;

public class NioAsyncExecutor extends AbstractAsyncExecutor implements Runnable {

	private static final boolean DISABLE_KEYSET_OPTIMIZATION = SystemPropertyUtil.getBoolean("org.jfw.jina.util.concurrent.spi.NioAsyncExecutor", false);
	private static final long SCHEDULE_PURGE_INTERVAL = SystemPropertyUtil.getLong("org.jfw.jina.util.concurrent.spi.SCHEDULE_PURGE_INTERVAL",
			TimeUnit.SECONDS.toNanos(1));
	private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
	private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

	static {
		final String key = "sun.nio.ch.bugLevel";
		final String buglevel = SystemPropertyUtil.get(key);
		if (buglevel == null) {
			try {
				AccessController.doPrivileged(new PrivilegedAction<Void>() {
					public Void run() {
						System.setProperty(key, "");
						return null;
					}
				});
			} catch (final SecurityException e) {
			}
		}

		int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
		if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
			selectorAutoRebuildThreshold = 0;
		}
		SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;
	}

	private Selector selector;
	private Selector unwrappedSelector;
	private SelectedSelectionKeySet selectedKeys;

	private volatile int ioRatio = 50;
	private boolean needsToSelectAgain;

	private final SelectorProvider provider;

	private Selector openSelector() {
		final Selector unwrappedSelector;
		try {
			unwrappedSelector = provider.openSelector();
		} catch (IOException e) {
			throw new RuntimeException("failed to open a new selector", e);
		}

		if (DISABLE_KEYSET_OPTIMIZATION) {
			this.unwrappedSelector = unwrappedSelector;
			return unwrappedSelector;
		}

		final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

		Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
			@Override
			public Object run() {
				try {
					return Class.forName("sun.nio.ch.SelectorImpl", false, System.getSecurityManager() == null ? ClassLoader.getSystemClassLoader()
							: AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
								@Override
								public ClassLoader run() {
									return ClassLoader.getSystemClassLoader();
								}
							}));
				} catch (Throwable cause) {
					return cause;
				}
			}
		});

		if (!(maybeSelectorImplClass instanceof Class) || !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
			this.unwrappedSelector =unwrappedSelector;
			return unwrappedSelector;
		}

		final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

		Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
			@Override
			public Object run() {
				try {
					Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
					Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

					Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField);
					if (cause != null) {
						return cause;
					}
					cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField);
					if (cause != null) {
						return cause;
					}

					selectedKeysField.set(unwrappedSelector, selectedKeySet);
					publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
					return null;
				} catch (NoSuchFieldException e) {
					return e;
				} catch (IllegalAccessException e) {
					return e;
				}
			}
		});

		if (maybeException instanceof Exception) {
			selectedKeys = null;
			// Exception e = (Exception) maybeException;
			// logger.trace("failed to instrument a special java.util.Set
			// into:{}", unwrappedSelector, e);
			this.unwrappedSelector =unwrappedSelector;
			return unwrappedSelector;
		}
		selectedKeys = selectedKeySet;
		// logger.trace("instrumented a special java.util.Set into: {}",
		// unwrappedSelector);
		this.unwrappedSelector =unwrappedSelector;
		return new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet);
	}

	/**
	 * Boolean that controls determines if a blocked Selector.select should
	 * break out of its selection process. In our case we use a timeout for the
	 * select method and the select method will block for that time unless waken
	 * up.
	 */
	private final AtomicBoolean wakenUp = new AtomicBoolean();

	public NioAsyncExecutor(AsyncExecutorGroup group, Runnable closeTask, SelectorProvider selectorProvider,Map<Object,Object> reses) {
		super(group, closeTask);
		this.provider = selectorProvider;
		selector = openSelector();
		this.objCache .putAll(reses);
	}

	int selectNow() throws IOException {
		try {
			return selector.selectNow();
		} finally {
			if (wakenUp.get()) {
				selector.wakeup();
			}
		}
	}

	private void select(boolean oldWakenUp) throws IOException {
		Selector selector = this.selector;
		try {
			int selectCnt = 0;
			long currentTimeNanos = System.nanoTime();
			long selectDeadLineNanos = currentTimeNanos
					+ (numDTask > 0 ? Math.max(0, hDTask.next.time - (currentTimeNanos - START_TIME)) : SCHEDULE_PURGE_INTERVAL);
			for (;;) {
				long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
				if (timeoutMillis <= 0) {
					if (selectCnt == 0) {
						selector.selectNow();
						selectCnt = 1;
					}
					break;
				}

				// If a task was submitted when wakenUp value was true, the task
				// didn't get a chance to call
				// Selector#wakeup. So we need to check task queue again before
				// executing select operation.
				// If we don't, the task might be pended until select operation
				// was timed out.
				// It might be pended until idle timeout if IdleStateHandler
				// existed in pipeline.
				if (hasSyncTask() && wakenUp.compareAndSet(false, true)) {
					selector.selectNow();
					selectCnt = 1;
					break;
				}

				int selectedKeys = selector.select(timeoutMillis);
				selectCnt++;

				if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasSyncTask() || hasReadyDelayTask()) {
					// - Selected something,
					// - waken up by user, or
					// - the task queue has a pending task.
					// - a scheduled task is ready for processing
					break;
				}
				if (Thread.interrupted()) {
					// Thread was interrupted so reset selected keys and break
					// so we not run into a busy loop.
					// As this is most likely a bug in the handler of the user
					// or it's client library we will
					// also log it.
					//
					// See https://github.com/netty/netty/issues/2426
					// if (logger.isDebugEnabled()) {
					// logger.debug("Selector.select() returned prematurely
					// because " +
					// "Thread.currentThread().interrupt() was called. Use " +
					// "NioEventLoop.shutdownGracefully() to shutdown the
					// NioEventLoop.");
					// }
					selectCnt = 1;
					break;
				}

				long time = System.nanoTime();
				if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
					// timeoutMillis elapsed without anything selected.
					selectCnt = 1;
				} else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
					// The selector returned prematurely many times in a row.
					// Rebuild the selector to work around the problem.
					// logger.warn("Selector.select() returned prematurely {}
					// times in a row; rebuilding Selector {}.", selectCnt,
					// selector);

					rebuildSelector();
					selector = this.selector;

					// Select again to populate selectedKeys.
					selector.selectNow();
					selectCnt = 1;
					break;
				}

				currentTimeNanos = time;
			}

			if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
				// if (logger.isDebugEnabled()) {
				// logger.debug("Selector.select() returned prematurely {} times
				// in a row for Selector {}.", selectCnt - 1, selector);
				// }
			}
		} catch (CancelledKeyException e) {
			// if (logger.isDebugEnabled()) {
			// logger.debug(CancelledKeyException.class.getSimpleName() + "
			// raised by a Selector {} - JDK bug?", selector, e);
			// }
			// Harmless exception - log anyway
		}
	}

	public void handleRunningTask() {
		try {
			if (nRTask > 0) {
				selectNow();
			} else {
				select(wakenUp.getAndSet(false));
				if (wakenUp.get()) {
					selector.wakeup();
				}
			}
			needsToSelectAgain = false;
			final int ioRatio = this.ioRatio;
			if (ioRatio == 100) {
				try {
					processSelectedKeys();
				} finally {
					runRunningTasks();
				}
			} else {
				final long ioStartTime = System.nanoTime();
				try {
					processSelectedKeys();
				} finally {
					final long ioTime = System.nanoTime() - ioStartTime;
					runRunningTasks(ioTime * (100 - ioRatio) / ioRatio);
				}
			}
		} catch (Throwable t) {
			handleLoopException(t);
		}
	}

	private void processSelectedKeys() {
		if (selectedKeys != null) {
			processSelectedKeysOptimized();
		} else {
			processSelectedKeysPlain(selector.selectedKeys());
		}
	}

	private void processSelectedKeysOptimized() {
		for (int i = 0; i < selectedKeys.size; ++i) {
			final SelectionKey k = selectedKeys.keys[i];
			// null out entry in the array to allow to have it GC'ed once the
			// Channel close
			// See https://github.com/netty/netty/issues/2363
			selectedKeys.keys[i] = null;
			processSelectedKey(k, (AsyncChannel) k.attachment());

			if (needsToSelectAgain) {
				// null out entries in the array to allow to have it GC'ed once
				// the Channel close
				// See https://github.com/netty/netty/issues/2363
				selectedKeys.reset(i + 1);

				selectAgain();
				i = -1;
			}
		}
	}

	public Selector unwrappedSelector() {
		return unwrappedSelector;
	}

	private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
		// check if the set is empty and if so just return to not create garbage
		// by
		// creating a new Iterator every time even if there is nothing to
		// process.
		// See https://github.com/netty/netty/issues/597
		if (selectedKeys.isEmpty()) {
			return;
		}

		Iterator<SelectionKey> i = selectedKeys.iterator();
		for (;;) {
			final SelectionKey k = i.next();
			final AsyncChannel a = (AsyncChannel) k.attachment();
			i.remove();
			processSelectedKey(k, a);
			if (!i.hasNext()) {
				break;
			}
			if (needsToSelectAgain) {
				selectAgain();
				selectedKeys = selector.selectedKeys();

				// Create the iterator again to avoid
				// ConcurrentModificationException
				if (selectedKeys.isEmpty()) {
					break;
				} else {
					i = selectedKeys.iterator();
				}
			}
		}
	}

	private void processSelectedKey(SelectionKey k, AsyncChannel ch) {
		if (!k.isValid()) {
			ch.close();
			return;
		}
		try {
			int readyOps = k.readyOps();
			// We first need to call finishConnect() before try to trigger a
			// read(...) or write(...) as otherwise
			// the NIO JDK channel implementation may throw a
			// NotYetConnectedException.
			if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
				// remove OP_CONNECT as otherwise Selector.select(..) will
				// always return without blocking
				// See https://github.com/netty/netty/issues/924
				int ops = k.interestOps();
				ops &= ~SelectionKey.OP_CONNECT;
				k.interestOps(ops);
				ch.connected();
			}

			// Process OP_WRITE first as we may be able to write some queued
			// buffers and so free memory.
			if ((readyOps & SelectionKey.OP_WRITE) != 0) {
				ch.write();
			}

			// Also check for readOps of 0 to workaround possible JDK bug which
			// may otherwise lead
			// to a spin loop
			if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
				ch.read();
			}
		} catch (CancelledKeyException ignored) {
			ch.close();
		}
	}

	private void selectAgain() {
		needsToSelectAgain = false;
		try {
			selector.selectNow();
		} catch (Throwable t) {
			// logger.warn("Failed to update SelectionKeys.", t);
		}
	}

	private void closeAll() {
		selectAgain();
		Set<SelectionKey> keys = selector.keys();
		// Collection<AsyncChannel> channels = new
		// ArrayList<AsyncChannel>(keys.size());
		for (SelectionKey k : keys) {
			AsyncChannel channel = (AsyncChannel) k.attachment();
			channel.close();
		}
	}

	private static void handleLoopException(Throwable t) {
		// logger.warn("Unexpected exception in the selector loop.", t);
		// Prevent possible consecutive immediate failures that lead to
		// excessive CPU consumption.
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// Ignore.
		}
	}

	public void rebuildSelector() {
		if (!inLoop()) {
			submit(new AsyncTask() {
				@Override
				public void failed(Throwable exc, AsyncExecutor executor) {
				}

				@Override
				public void execute(AsyncExecutor executor) throws Throwable {
					rebuildSelector0();
				}

				@Override
				public void completed(AsyncExecutor executor) {
				}

				@Override
				public void cancled(AsyncExecutor executor) {
				}
			});
			return;
		}
		rebuildSelector0();
	}

	private void rebuildSelector0() {
		final Selector oldSelector = selector;
		final Selector newSelector;

		if (oldSelector == null) {
			return;
		}

		try {
			newSelector = openSelector();
		} catch (Exception e) {
			return;
		}
		// int nChannels = 0;
		for (SelectionKey key : oldSelector.keys()) {
			AsyncChannel channel = (AsyncChannel) key.attachment();
			try {
				if (!key.isValid() || key.channel().keyFor(newSelector) != null) {
					continue;
				}
				int interestOps = key.interestOps();
				key.cancel();
				SelectionKey newKey = key.channel().register(newSelector, interestOps, channel);
				channel.setSelectionKey(newKey);
				// nChannels++;
			} catch (Exception e) {
				channel.close();
			}
		}

		selector = newSelector;

		try {
			oldSelector.close();
		} catch (Throwable t) {
		}
	}

	@Override
	protected void wakeup() {
		if (wakenUp.compareAndSet(false, true)) {
			selector.wakeup();
		}
	}

	@Override
	public void cleanup() {
		try {
			this.closeAll();
		} catch (Throwable e) {
			//
		}
		try {
			selector.close();
		} catch (IOException e) {
			// logger.warn("Failed to close a selector.", e);
		}

	}

	@Override
	public List<AsyncTask> pendingTasks() {
		return new ArrayList<AsyncTask>();
	}
}
