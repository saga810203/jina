package org.jfw.jina.core.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.TagQueue.TagQueueHandler;

public abstract class AbstractNioAsyncChannel<T extends NioAsyncExecutor> implements NioAsyncChannel {
	public static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

	protected T executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
	protected final TagQueue<ByteBuffer, TaskCompletionHandler> outputCache;
	protected ByteBuffer cacheBuffer;
	protected Throwable writeException;
	protected int ridx;
	protected int widx;
	protected ByteBuffer rbuffer;
	protected byte[] rBytes;
	protected int rlen;

	protected AbstractNioAsyncChannel(AbstractNioAsyncChannel<? extends T> channel) {
		assert channel.executor.inLoop();
		this.javaChannel = channel.javaChannel;
		this.executor = channel.executor;
		this.key = channel.key;
		this.key.attach(this);
		this.outputCache = channel.outputCache;
		this.ridx = channel.ridx;
		this.widx = channel.widx;
		this.rbuffer = channel.rbuffer;
		this.rBytes = channel.rBytes;
		this.rlen = channel.rlen;
	}

	protected AbstractNioAsyncChannel(T executor, SocketChannel javaChannel) {
		assert executor.inLoop();
		this.outputCache = executor.newTagQueue();
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.rbuffer = ByteBuffer.allocate(8192);
		this.rBytes = rbuffer.array();
		this.rlen = this.rBytes.length;
	}

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		this.key = this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
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
		// this.inputCache.clear(RELEASE_INPUT_BUF);
		this.outputCache.clear(this.WRITE_ERROR_HANDLER);
	}

	protected abstract void handleRead(int len);

	protected void compactReadBuffer() {
		if (this.ridx > 0) {
			if (ridx == this.widx) {
				this.rbuffer.clear();
				this.ridx = this.widx = 0;
			} else {
				this.widx -= this.ridx;
				System.arraycopy(this.rbuffer, this.ridx, this.rbuffer, 0, this.widx);
				this.ridx = 0;
				this.rbuffer.clear().position(this.widx);
			}
		}
	}

	protected void clearReadBuffer() {
		this.ridx = this.widx = 0;
		this.rbuffer.clear();
	}

	@Override
	public void read() {
		int rc = 0;
		try {
			rc = this.javaChannel.read(this.rbuffer);
		} catch (ClosedChannelException e) {
			int len = this.widx - this.ridx;
			if (len > 0) {
				this.handleRead(len);
			}
			handleRead(-1);
			this.cleanOpRead();
			return;
		} catch (IOException e) {
			int len = this.widx - this.ridx;
			if (len > 0) {
				this.handleRead(len);
			}
			handleRead(-1);
			this.cleanOpRead();
			this.hanldReadException(e);
			return;
		}
		if (rc == 0) {
			int len = this.widx - this.ridx;
			if (len > 0) {
				this.handleRead(len);
			}
			return;
		} else if (rc > 0) {
			this.widx += rc;
			this.handleRead(this.widx - this.ridx);
			return;

		} else {
			int len = this.widx - this.ridx;
			if (len > 0) {
				this.handleRead(len);
			}
			this.handleRead(-1);
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

	protected void writeData(byte[] buf, int index, int len) {
		assert buf != null;
		assert index >= 0;
		assert len > 0;
		assert buf.length >= (index + len);
		if (this.javaChannel != null) {
			if (null == this.cacheBuffer) {
				this.cacheBuffer = ByteBuffer.allocate(8192);
			} else {
				if (!cacheBuffer.hasRemaining()) {
					cacheBuffer.flip();
					this.outputCache.offer(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			while (len > 0) {
				int wl = Integer.min(cacheBuffer.remaining(), len);
				len -= wl;
				if (len > 0) {
					cacheBuffer.flip();
					outputCache.offer(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
		}
	}
//	
//	protected void writeData(byte[] buf, int index, int len,TaskCompletionHandler task) {
//		assert buf != null;
//		assert index >= 0;
//		assert len > 0;
//		assert buf.length >= (index + len);
//		if (this.javaChannel != null) {
//			if (null == this.cacheBuffer) {
//				this.cacheBuffer = ByteBuffer.allocate(8192);
//			} else {
//				if (!cacheBuffer.hasRemaining()) {
//					cacheBuffer.flip();
//					this.outputCache.offer(cacheBuffer);
//					cacheBuffer = ByteBuffer.allocate(8192);
//				}
//			}
//			while (len > 0) {
//				int wl = Integer.min(cacheBuffer.remaining(), len);
//				len -= wl;
//				if (len > 0) {
//					cacheBuffer.flip();
//					outputCache.offer(cacheBuffer);
//					cacheBuffer = ByteBuffer.allocate(8192);
//				}
//			}
//			this.cacheBuffer.flip();
//			this.outputCache.offer(cacheBuffer,task);
//			this.cacheBuffer = null;
//			this.setOpWrite();
//		}
//	}

	protected void writeByteData(byte value) {
		if (this.javaChannel != null) {
			if (null == this.cacheBuffer) {
				this.cacheBuffer = ByteBuffer.allocate(8192);
			} else {
				if (!cacheBuffer.hasRemaining()) {
					cacheBuffer.flip();
					this.outputCache.offer(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			this.cacheBuffer.put(value);
		}
	}

	protected void flushData(byte[] buf, int index, int len, TaskCompletionHandler task) {
		assert buf != null;
		assert index >= 0;
		assert len > 0;
		assert buf.length >= (index + len);
		if (this.javaChannel != null) {
			if (null == this.cacheBuffer) {
				this.cacheBuffer = ByteBuffer.allocate(8192);
			} else {
				if (!cacheBuffer.hasRemaining()) {
					cacheBuffer.flip();
					this.outputCache.offer(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			while (len > 0) {
				int wl = Integer.min(cacheBuffer.remaining(), len);
				len -= wl;
				if (len > 0) {
					cacheBuffer.flip();
					outputCache.offer(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			this.cacheBuffer.flip();
			this.outputCache.offer(this.cacheBuffer, task);
			this.cacheBuffer = null;
			this.setOpWrite();
		} else {
			task.failed(this.writeException, executor);
		}
	}

	protected void flushData(TaskCompletionHandler task) {
		assert task != null;
		if (this.javaChannel != null) {
			if (null == this.cacheBuffer) {
				this.outputCache.offer(EMPTY_BUFFER, task);
			} else {
				this.cacheBuffer.flip();
				this.outputCache.offer(this.cacheBuffer, task);
				this.cacheBuffer = null;
			}
			this.setOpWrite();
		}
	}

	@Override
	public void write() {
		TagNode tagNode = null;
		outputCache.peekTagNode();
		while ((tagNode = outputCache.peekTagNode()) != null) {
			ByteBuffer buf = tagNode.item();
			TaskCompletionHandler task = (TaskCompletionHandler) tagNode.tag();
			if (buf.hasRemaining()) {
				try {
					this.javaChannel.write(buf);
				} catch (IOException e) {
					this.writeException = e;
					this.close();
					return;
				}
				if (buf.hasRemaining()) {
					return;
				}
			}
			outputCache.unsafeShift();
			if (task != null) {
				executor.safeInvokeCompleted(task);
			}
		}
		this.cleanOpWrite();
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

	// private void ensureOutCapacity(int size){
	// if(this.cacheBuffer==null){
	// this.cacheBuffer = ByteBuffer.allocate(8192);
	// }else{
	// if(this.cacheBuffer.remaining()<size){
	// this.cacheBuffer.flip();
	// off
	// }
	// }
	// }
	//
	// protected void writeBoolean(boolean value) {
	// if(this.)
	//
	// if (!buf.writable(1)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeBoolean(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeByte(OutputBuf buf, int value) {
	// if (!buf.writable(1)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeByte(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeShort(OutputBuf buf, int value) {
	// if (!buf.writable(2)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeShort(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeShortLE(OutputBuf buf, int value) {
	// if (!buf.writable(2)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeShortLE(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeMedium(OutputBuf buf, int value) {
	// if (!buf.writable(3)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeMedium(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeMediumLE(OutputBuf buf, int value) {
	// if (!buf.writable(3)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeMediumLE(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeInt(OutputBuf buf, int value) {
	// if (!buf.writable(4)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeInt(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeIntLE(OutputBuf buf, int value) {
	// if (!buf.writable(4)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeIntLE(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeLong(OutputBuf buf, long value) {
	// if (!buf.writable(8)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeLong(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeLongLE(OutputBuf buf, long value) {
	// if (!buf.writable(8)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeLongLE(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeChar(OutputBuf buf, int value) {
	// if (!buf.writable(2)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeChar(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeFloat(OutputBuf buf, float value) {
	// if (!buf.writable(4)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeFloat(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeFloatLE(OutputBuf buf, float value) {
	// if (!buf.writable(4)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeFloatLE(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeDouble(OutputBuf buf, double value) {
	// if (!buf.writable(8)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeDouble(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeDoubleLE(OutputBuf buf, double value) {
	// if (!buf.writable(8)) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// buf.writeDouble(value);
	// return buf;
	// }
	//
	// protected OutputBuf writeBytes(OutputBuf buf, byte[] src, int srcIndex,
	// int length) {
	// if (!buf.writable()) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// while (length > 0) {
	// int canWriteCount = buf.writableBytes();
	// if (length > canWriteCount) {
	// buf.writeBytes(src, srcIndex, canWriteCount);
	// srcIndex += canWriteCount;
	// length -= canWriteCount;
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// } else {
	// buf.writeBytes(src, srcIndex, length);
	// break;
	// }
	// }
	// return buf;
	// }

	// protected OutputBuf writeAscii(OutputBuf buf, String src) {
	// byte[]
	//
	// if (!buf.writable()) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// int idx = 0;
	// int len = src.length();
	// do {
	// int wlen = Integer.min(buf.writableBytes() + idx, len);
	// while (idx < wlen) {
	// buf.writeByte(src.charAt(idx++));
	// }
	// if (idx < len) {
	// this.write(buf.input());
	// buf.release();
	// buf = executor.allocBuffer();
	// }
	// } while (idx < len);
	// return buf;
	// }

	protected final TagQueueHandler<ByteBuffer, TaskCompletionHandler> WRITE_ERROR_HANDLER = new TagQueueHandler<ByteBuffer, TaskCompletionHandler>() {
		@Override
		public void process(ByteBuffer item, TaskCompletionHandler tag) {
			if (tag != null) {
				((TaskCompletionHandler) tag).failed(writeException, executor);
			}
		}
	};
}
