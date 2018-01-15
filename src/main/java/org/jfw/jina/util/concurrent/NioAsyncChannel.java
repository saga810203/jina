package org.jfw.jina.util.concurrent;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.EmptyBuf;
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
		SocketChannel jc = this.javaChannel;
		SelectionKey k = this.key;
		this.javaChannel = null;
		this.key = null;
		if (inputHead.next != inputEnd) {
			this.freeWriteList(inputHead.next, inputEnd.prev);
			inputHead.next = inputEnd;
			inputEnd.prev = inputHead;
		}
		if (outHead.next != outEnd) {
			this.freeWriteList(outHead.next, outEnd.prev);
			this.outHead.next = this.outEnd;
			this.outEnd.prev = this.outHead;
		}
		if (k != null) {
			try {
				k.cancel();
			} catch (Exception e) {
			}
		}

		if (jc != null) {
			try {
				jc.close();
			} catch (Throwable t) {

			}
		}
	}

	protected void appendInput(InputBuf buf) {
		LinkedNode node = executor.getNode();
		node.item = buf;
		inputEnd.before(node);
	}

	protected InputBuf peekInput() {
		return (InputBuf) inputHead.next.item;
	}

	protected InputBuf pollInput() {
		LinkedNode node = inputHead.next;
		if (node == inputEnd) {
			return null;
		} else {
			LinkedNode next = node.next;
			inputHead.next = next;
			next.prev = inputHead;
			InputBuf ret = (InputBuf) node.item;
			executor.releaseSingle(node);
			return ret;
		}
	}

	protected void removeInput() {
		assert inputHead.next != inputEnd;
		LinkedNode node = inputHead.next;
		LinkedNode next = node.next;
		inputHead.next = next;
		next.prev = inputHead;
		InputBuf ret = (InputBuf) node.item;
		executor.releaseSingle(node);
		ret.release();
	}

	protected abstract void handleRead(InputBuf buf, int len);

	@Override
	public final void read() {
		int rc = 0;
		for (;;) {
			OutputBuf buf = executor.alloc();
			try {
				rc = buf.writeBytes(this.javaChannel);
				if (rc > 0) {
					InputBuf ibuf = buf.input();
					this.handleRead(ibuf, rc);
					ibuf.release();
				} else if (rc < 0) {
					this.closeInput = true;
					this.handleRead(EmptyBuf.INSTANCE, -1);
					this.cleanOpRead();
					return;
				}else{
					return;
				}
			} catch (ClosedChannelException e) {
				this.closeInput = true;
				handleRead(EmptyBuf.INSTANCE, -1);
				this.cleanOpRead();
				return;
			} catch (IOException e) {
				handleRead(EmptyBuf.INSTANCE, -1);
				this.cleanOpRead();
				this.hanldReadException(e);
				return;
			} finally {
				buf.release();
			}
		}
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
		if (this.javaChannel != null) {
			LinkedNode node = executor.getNode();
			node.item = buf;
			node.tag = task;
			outEnd.before(node);
			this.setOpWrite();
		} else {
			if (task != null)
				task.cancled(executor);
			if (buf != null)
				buf.release();
		}
	}

	protected void freeWriteList(LinkedNode begin, LinkedNode end) {
		LinkedNode node = begin;
		this.cleanOpWrite();

		while (node != end) {
			write((InputBuf) node.item, (AsyncTask) node.tag);
		}
		write((InputBuf) end.item, (AsyncTask) end.tag);
		end.next = null;
		begin.prev = null;
		executor.release(begin);
	}

	protected void write(LinkedNode begin, LinkedNode end) {
		if (this.javaChannel != null) {
			outEnd.before(begin, end);
			this.setOpWrite();
		} else {
			freeWriteList(begin, end);
		}
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

	protected InputBuf removeFirstReadBuf() {
		assert executor.inLoop() && inputHead.next != inputEnd;
		LinkedNode node = inputHead.next;
		LinkedNode next = node.next;
		inputHead.next = next;
		next.prev = inputHead;
		((InputBuf) node.item).release();
		executor.releaseSingle(node);
		return (InputBuf) next.item;
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

}
