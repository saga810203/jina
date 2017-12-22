package org.jfw.jina.util.concurrent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public abstract class AbstractAsyncServerChannel implements AsyncServerChannel {
	private static final AtomicIntegerFieldUpdater STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(AbstractAsyncServerChannel.class, "state");
	private static final int STATE_INIT = 0;
	private static final int STATE_STARTING = 1;
	private static final int STATE_STARTED = 2;
	private static final int STATE_STOPED = 4;
	public static final Throwable  CancledException = new IOException("start cancleed");

	@SuppressWarnings("unused")
	private int state = STATE_INIT;
	protected ServerSocketChannel javaChannel;
	private final AsyncExecutorGroup group;
	private final AsyncExecutorGroup childGroup;
	@SuppressWarnings("unused")
	private SelectionKey selectionKey;
	private volatile Throwable error;

	public AbstractAsyncServerChannel(AsyncExecutorGroup group, AsyncExecutorGroup childGroup) {
		this.childGroup = childGroup;
		this.group = group;
	}

	@Override
	public void read() {
		SocketChannel channel = null;
		try {
			channel = javaChannel.accept();
		} catch (IOException e) {
		}
		if (channel != null) {
			this.accectClient(channel, childGroup.next());
		}
	}

	@Override
	public void write() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
	}

	@Override
	public void close() {
		if (STATE_UPDATER.compareAndSet(this, STATE_STARTED, STATE_STOPED)) {
			try {
				this.javaChannel.close();
			} catch (IOException e) {
			}
		}
	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();
	}


	@SuppressWarnings({ "unchecked", "unchecked" })
	@Override
	public void start(InetSocketAddress address, int backlog) throws Throwable {
		if (STATE_UPDATER.compareAndSet(this, STATE_INIT, STATE_STARTING)) {
			try {
				this.javaChannel = ServerSocketChannel.open();
				this.javaChannel.configureBlocking(false);
				this.config();
				this.javaChannel.bind(address,
						backlog > 0 ? backlog : SystemPropertyUtil.getInt("org.jfw.jina.util.concurrent.DefaultAsyncServerChannel.backlog", 1024));
			} catch (IOException e) {
				try {
					if (null != this.javaChannel) {
						this.javaChannel.close();
					}
				} catch (Throwable ee) {
				}
				STATE_UPDATER.set(this, STATE_STOPED);
				throw e;
			}
			final CountDownLatch clock = new CountDownLatch(1);
			group.next().submit(new AsyncTask() {
				@Override
				public void failed(Throwable exc, AsyncExecutor executor) {
					STATE_UPDATER.set(this, STATE_STOPED);
					AbstractAsyncServerChannel.this.selectionKey = null;
					AbstractAsyncServerChannel.this.error = exc;
					try {
						javaChannel.close();
					} catch (Throwable t) {
					}
					clock.countDown();
				}

				@Override
				public void execute(AsyncExecutor executor) throws Throwable {
					Selector selector = ((NioAsyncExecutor) executor).unwrappedSelector();
					AbstractAsyncServerChannel.this.selectionKey = javaChannel.register(selector, SelectionKey.OP_ACCEPT, AbstractAsyncServerChannel.this);
				}

				@Override
				public void completed(AsyncExecutor executor) {
					STATE_UPDATER.set(this, STATE_STARTED);
					clock.countDown();
				}

				@Override
				public void cancled(AsyncExecutor executor) {
					AbstractAsyncServerChannel.this.selectionKey = null;
					AbstractAsyncServerChannel.this.error = CancledException;
					try {
						javaChannel.close();
					} catch (Throwable t) {
					}
					clock.countDown();					
				}
			});
			for(;;){
			try {
				clock.await();
				break;
			} catch (InterruptedException e) {
				
			}
			}
			if(null!= this.error) throw this.error;
		}
	}

	@Override
	public void config() {
	}

}
