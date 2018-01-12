package org.jfw.jina.util.concurrent;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.LinkedNode;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public abstract class NioAsyncChannel<T extends NioAsyncExecutor> implements AsyncChannel {
	protected T executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
	protected final LinkedNode inputHead;
	protected final LinkedNode inputEnd;
	protected final LinkedNode outHead;
	protected final LinkedNode outEnd;
	protected long lastReadCount;
	protected int lastReadNum;
	protected boolean closeInput = false;

	protected NioAsyncChannel(NioAsyncChannel<? extends T> channel) {
		assert channel.executor.inLoop();
		this.javaChannel = channel.javaChannel;
		this.executor = channel.executor;
		this.key = channel.key;
		this.key.attach(this);
		this.inputHead = channel.inputHead;
		this.inputEnd = channel.inputEnd;
		this.outEnd = channel.outEnd;
		this.outHead = channel.outHead;
		this.lastReadCount = channel.lastReadCount;
		this.lastReadNum = channel.lastReadNum;
		this.afterRegister();
	}

	protected NioAsyncChannel(T executor, SocketChannel javaChannel) {
		this.inputHead = this.executor.getNode();
		this.inputEnd = this.executor.getNode();
		this.inputHead.next = this.inputEnd;
		this.inputEnd.prev = this.inputHead;

		this.outHead = this.executor.getNode();
		this.outEnd = this.executor.getNode();
		this.outHead.next = this.outEnd;
		this.outEnd.prev = this.outHead;
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.executor.submit(new AsyncTask() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
				NioAsyncChannel.this.closeJavaChannel();
			}

			@Override
			public void execute(AsyncExecutor executor) throws Throwable {
				NioAsyncChannel.this.doRegister(executor);
			}

			@Override
			public void completed(AsyncExecutor executor) {
				NioAsyncChannel.this.afterRegister();
			}

			@Override
			public void cancled(AsyncExecutor executor) {
				NioAsyncChannel.this.closeJavaChannel();
			}
		});
	}

	protected void doRegister(AsyncExecutor executor) throws ClosedChannelException {
		assert this.executor == executor;
		assert this.javaChannel != null;
		this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
	}

	protected void afterRegister() {
	}

	protected void hanldReadException(IOException e) {
		this.closeJavaChannel();
	}

	protected void closeJavaChannel() {
		assert this.executor.inLoop();
		LinkedNode node = inputHead.next;
		while (node != inputEnd) {
			InputBuf buf = (InputBuf) node.item;
			if (buf != null) {
				buf.release();
				node.item = null;
			}
			node = node.next;
		}

		node = outHead.next;
		while (node != outEnd) {
			InputBuf buf = (InputBuf) node.item;
			AsyncTask task = (AsyncTask) node.tag;
			if (buf != null) {
				buf.release();
				node.item = null;
			}
			if (task != null) {
				task.cancled(executor);
			}
			node = node.next;
		}

		if (this.key != null) {
			try {
				this.key.cancel();
			} catch (Exception e) {
			}
			this.key = null;
		}

		if (this.javaChannel != null) {
			try {
				this.javaChannel.close();
			} catch (Throwable t) {

			}
			this.javaChannel = null;
		}
	}

	protected abstract void handleRead();
	@Override
	public final void read() {
		long rc = 0;
		int rn = 0;
		for (;;) {
			OutputBuf buf = executor.alloc();
			try {
				rn = buf.writeBytes(this.javaChannel);
				if (rn > 0) {
					rc += rn;
					LinkedNode node = executor.getNode();
					node.item = buf.input();
					buf.release();
					inputEnd.before(node);
				} else {
					lastReadCount = rc;
					lastReadNum = rn;
					buf.release();
					buf = null;
					if(rn<0){
						this.closeInput = true;
					}
					break;
				}
			} catch (IOException e) {
				this.hanldReadException(e);
				return;
			}
		}
		this.handleRead();
	}

	protected void handleWriteException(IOException e, InputBuf buf, AsyncTask task) {
		if (task != null)
			task.failed(e, executor);
		this.closeJavaChannel();
	}

	protected final void setOpRead() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_READ) == 0) {
			key.interestOps(interestOps | SelectionKey.OP_READ);
		}
	}

	protected final void cleanOpRead() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_READ) != 0) {
			key.interestOps(interestOps | ~SelectionKey.OP_READ);
		}
	}

	protected final void setOpWrite() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) == 0) {
			key.interestOps(interestOps | SelectionKey.OP_WRITE);
		}
	}

	protected final void cleanOpWrite() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_WRITE) != 0) {
			key.interestOps(interestOps | ~SelectionKey.OP_WRITE);
		}
	}

	protected void write(InputBuf buf, AsyncTask task) {
		LinkedNode node = executor.getNode();
		node.item = buf;
		node.tag = task;
		outEnd.before(node);
	}

	protected void write(LinkedNode begin, LinkedNode end) {
		outEnd.before(begin, end);
	}

	@Override
	public void write() {
		LinkedNode node = null;
		InputBuf buf = null;
		AsyncTask task = null;

		for (;;) {
			node = outHead.next;
			if (node == outEnd) {
				this.cleanOpWrite();
				return;
			}
			buf = (InputBuf) node.item;
			task = (AsyncTask) node.tag;
			if (buf.readable()) {
				try {
					buf.readBytes(this.javaChannel);
				} catch (IOException e) {
					this.handleWriteException(e, buf, task);
					return;
				}
				if (buf.readable()) {
					buf = null;
					node = null;
					return;
				} else {
					outHead.next = node.next;
					executor.releaseSingle(node);
					buf.release();
					buf = null;
					node = null;
					if (task != null) {
						task.completed(executor);
					}
				}
			} else {
				outHead.next = node.next;
				executor.releaseSingle(node);
				buf.release();
				buf = null;
				node = null;
				if (task != null) {
					task.completed(executor);
				}
			}
		}
	}
	
	protected InputBuf removeFirstReadBuf(){
		assert executor.inLoop() && inputHead.next!= inputEnd;
		LinkedNode node = inputHead.next;
		LinkedNode next = node.next;
		inputHead.next =next;
		next.prev = inputHead;
		((InputBuf)node.item).release();
		executor.releaseSingle(node);		
		return (InputBuf) next.item;
	}
	

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

}
