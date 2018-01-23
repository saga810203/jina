package org.jfw.jina.core.impl;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.TagQueue.TagQueueHandler;

public abstract class AbstractNioAsyncChannel<T extends NioAsyncExecutor> implements NioAsyncChannel {
	protected T executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
//	protected final TagQueue inputCache;
	protected final TagQueue outputCache;
	protected Throwable writeException;

	protected AbstractNioAsyncChannel(AbstractNioAsyncChannel<? extends T> channel) {
		assert channel.executor.inLoop();
		this.javaChannel = channel.javaChannel;
		this.executor = channel.executor;
		this.key = channel.key;
		this.key.attach(this);
//		this.inputCache = channel.inputCache;
		this.outputCache = channel.outputCache;
		this.afterRegister();
	}

	protected AbstractNioAsyncChannel(T executor, SocketChannel javaChannel) {
		assert executor.inLoop();
//		this.inputCache = executor.newTagQueue();
		this.outputCache = executor.newTagQueue();
		this.executor = executor;
		this.javaChannel = javaChannel;
	}

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
		this.afterRegister();
	}

	protected void afterRegister() {
	}

	protected void hanldReadException(IOException e) {
		this.close();
	}

	protected void closeJavaChannel() {
		assert this.executor.inLoop();
		SocketChannel jc = this.javaChannel;
		SelectionKey k = this.key;
		this.javaChannel = null;
		this.key = null;
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
//		this.inputCache.clear(RELEASE_INPUT_BUF);
		this.outputCache.clear(this.WRITE_ERROR_HANDLER);
	}

	protected abstract void handleRead(InputBuf buf, int len);

	@Override
	public final void read() {
		int rc = 0;
		for (;;) {
			OutputBuf buf = executor.allocBuffer();
			try {
				rc = buf.writeBytes(this.javaChannel);
				if (rc > 0) {
					InputBuf ibuf = buf.input();
					this.handleRead(ibuf, rc);
					ibuf.release();
				} else if (rc < 0) {
					this.handleRead(EmptyBuf.INSTANCE, -1);
					this.cleanOpRead();
					return;
				} else {
					return;
				}
			} catch (ClosedChannelException e) {
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

	// protected void handleWriteException(IOException e, InputBuf buf,
	// AsyncTask task) {
	// if (task != null)
	// task.failed(e, executor);
	// this.closeJavaChannel();
	// }

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

	protected void write(InputBuf buf, TaskCompletionHandler task) {
		assert buf != null;
		assert task != null;
		if (this.javaChannel != null) {
			if (outputCache.isEmpty()) {
				try {
					buf.readBytes(this.javaChannel);
				} catch (IOException e) {
					this.writeException = e;
					this.close();
					task.failed(e, executor);
					return;
				}
				if (buf.readable()) {
					this.setOpWrite();
				} else {
					buf.release();
					task.completed(executor);
					return;
				}
			}
			outputCache.offer(buf, task);
		} else {
			if (task != null)
				task.failed(this.writeException, executor);
			if (buf != null)
				buf.release();
		}
	}

	protected void write(InputBuf buf) {
		assert buf != null;
		assert buf.readable();
		if (this.javaChannel != null) {
			if (this.outputCache.isEmpty()) {
				try {
					buf.readBytes(this.javaChannel);
				} catch (IOException e) {
					this.writeException = e;
					this.close();
					return;
				}
				if (buf.readable()) {
					this.setOpWrite();
				} else {
					buf.release();
					return;
				}
			}
			outputCache.offer(buf, null);
		}
	}

	@Override
	public void write() {
		TagNode tagNode = null;
		outputCache.peekTagNode();
		while ((tagNode = outputCache.peekTagNode()) != null) {
			InputBuf buf = (InputBuf) tagNode.item();
			TaskCompletionHandler task = (TaskCompletionHandler) tagNode.tag();
			if (buf.readable()) {
				try {
					buf.readBytes(this.javaChannel);
				} catch (IOException e) {
					this.writeException = e;
					this.close();
					return;
				}
				if (buf.readable()) {
					return;
				}
			}
			outputCache.unsafeShift();
			if (task != null) {
				task.completed(executor);
			}
		}
		this.cleanOpWrite();
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

	protected OutputBuf writeBoolean(OutputBuf buf, boolean value) {
		if (!buf.writable(1)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeBoolean(value);
		return buf;
	}

	protected OutputBuf writeByte(OutputBuf buf, int value) {
		if (!buf.writable(1)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeByte(value);
		return buf;
	}

	protected OutputBuf writeShort(OutputBuf buf, int value) {
		if (!buf.writable(2)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeShort(value);
		return buf;
	}

	protected OutputBuf writeShortLE(OutputBuf buf, int value) {
		if (!buf.writable(2)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeShortLE(value);
		return buf;
	}

	protected OutputBuf writeMedium(OutputBuf buf, int value) {
		if (!buf.writable(3)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeMedium(value);
		return buf;
	}

	protected OutputBuf writeMediumLE(OutputBuf buf, int value) {
		if (!buf.writable(3)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeMediumLE(value);
		return buf;
	}

	protected OutputBuf writeInt(OutputBuf buf, int value) {
		if (!buf.writable(4)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeInt(value);
		return buf;
	}

	protected OutputBuf writeIntLE(OutputBuf buf, int value) {
		if (!buf.writable(4)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeIntLE(value);
		return buf;
	}

	protected OutputBuf writeLong(OutputBuf buf, long value) {
		if (!buf.writable(8)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeLong(value);
		return buf;
	}

	protected OutputBuf writeLongLE(OutputBuf buf, long value) {
		if (!buf.writable(8)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeLongLE(value);
		return buf;
	}

	protected OutputBuf writeChar(OutputBuf buf, int value) {
		if (!buf.writable(2)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeChar(value);
		return buf;
	}

	protected OutputBuf writeFloat(OutputBuf buf, float value) {
		if (!buf.writable(4)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeFloat(value);
		return buf;
	}

	protected OutputBuf writeFloatLE(OutputBuf buf, float value) {
		if (!buf.writable(4)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeFloatLE(value);
		return buf;
	}

	protected OutputBuf writeDouble(OutputBuf buf, double value) {
		if (!buf.writable(8)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeDouble(value);
		return buf;
	}

	protected OutputBuf writeDoubleLE(OutputBuf buf, double value) {
		if (!buf.writable(8)) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		buf.writeDouble(value);
		return buf;
	}

	protected OutputBuf writeBytes(OutputBuf buf, byte[] src, int srcIndex, int length) {
		if (!buf.writable()) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		while (length > 0) {
			int canWriteCount = buf.writableBytes();
			if (length > canWriteCount) {
				buf.writeBytes(src, srcIndex, canWriteCount);
				srcIndex += canWriteCount;
				length -= canWriteCount;
				this.write(buf.input());
				buf.release();
				buf = executor.allocBuffer();
			} else {
				buf.writeBytes(src, srcIndex, length);
				break;
			}
		}
		return buf;
	}

	protected OutputBuf writeAscii(OutputBuf buf, String src) {
		if (!buf.writable()) {
			this.write(buf.input());
			buf.release();
			buf = executor.allocBuffer();
		}
		int idx = 0;
		int len = src.length();
		do {
			int wlen = Integer.min(buf.writableBytes() + idx, len);
			while (idx < wlen) {
				buf.writeByte(src.charAt(idx++));
			}
			if (idx < len) {
				this.write(buf.input());
				buf.release();
				buf = executor.allocBuffer();
			}
		} while (idx < len);
		return buf;
	}

	protected final TagQueueHandler WRITE_ERROR_HANDLER = new TagQueueHandler() {
		@Override
		public void process(Object item, Object tag) {
			((InputBuf) item).release();
			if (tag != null) {
				((TaskCompletionHandler) tag).failed(writeException, executor);
			}
		}
	};
	// protected final TagQueueMatcher WRITE_HANDLER = new TagQueueMatcher(){
	// @Override
	// public boolean match(Object item, Object tag) {
	//
	// }
	//
	// };



}
