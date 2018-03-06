package org.jfw.jina.core.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.TagQueue.TagQueueHandler;

public abstract class AbstractNioAsyncChannel<T extends NioAsyncExecutor> implements NioAsyncChannel {
	private final static Logger LOG = LogFactory.getLog(AbstractNioAsyncChannel.class);
	public static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

	protected String channelId;
	protected T executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
	protected TagQueue<ByteBuffer, TaskCompletionHandler> outputCache;
	private ByteBuffer cacheBuffer;
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
		try {
			this.javaChannel.configureBlocking(false);
		} catch (IOException e) {
			LOG.error(channelId + " configBlocking(false )  error", e);
		}
		this.key = this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
		this.afterRegister();
	}

	@Override
	public void setChannelId(String id) {
		this.channelId = id;
	}

	protected void afterRegister() {
	}

	// protected void hanldReadException(IOException e) {
	// if (LOG.enableWarn()) {
	// LOG.warn(this.channelId + " handle read error", e);
	// }
	// this.close();
	// }

	protected boolean closeJavaChannel() {
		assert this.executor.inLoop();
		if (null != this.javaChannel) {
			SocketChannel jc = this.javaChannel;
			SelectionKey k = this.key;
			this.javaChannel = null;
			this.key = null;
			if (k != null) {
				try {
					k.interestOps(0);
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
			this.outputCache.clear(this.WRITE_ERROR_HANDLER);
			return true;
		}
		return false;
	}

	public abstract boolean handleRead(int len);

	protected void compactReadBuffer() {
		if (this.ridx > 0) {
			if (ridx == this.widx) {
				this.rbuffer.clear();
				this.ridx = this.widx = 0;
			} else {
				this.widx -= this.ridx;
				System.arraycopy(this.rBytes, this.ridx, this.rBytes, 0, this.widx);
				this.ridx = 0;
				this.rbuffer.clear().position(this.widx);
			}
		}
	}

	protected void clearReadBuffer() {
		this.ridx = this.widx = 0;
		this.rbuffer.clear();
	}

	protected void ensureCapacity4ReadBuffer(int increment) {
		int nlen = this.widx + increment;
		if (nlen > this.rlen) {
			nlen -= this.ridx;
			if (nlen > this.rlen) {
				byte[] obs = this.rBytes;
				while (nlen > this.rlen) {
					this.rlen += 8192;
				}
				this.rbuffer = ByteBuffer.allocate(this.rlen);
				this.rbuffer.order(ByteOrder.BIG_ENDIAN);
				this.rBytes = this.rbuffer.array();
				this.widx -= this.ridx;
				this.rbuffer.put(obs, this.ridx, this.widx);
				this.ridx = 0;
			} else {
				this.compactReadBuffer();
			}
		}
	}

	@Override
	public void read() {
		assert LOG.assertDebug(this.channelId, " invoke read()");
		if (null != this.javaChannel) {
			int rc = 0;
			try {
				rc = this.javaChannel.read(this.rbuffer);
			} catch (ClosedChannelException e) {
				int len = this.widx - this.ridx;
				if (len > 0) {
					this.handleRead(len);
				}
				assert null != this.javaChannel;
				// if (this.javaChannel != null) {
				this.cleanOpRead();
				handleInputClose();
				// }
				return;
			} catch (IOException e) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " javaChannel.read  error", e);
				}
				int len = this.widx - this.ridx;
				if (len > 0) {
					this.handleRead(len);
				}
				assert null != this.javaChannel;
				// if (this.javaChannel != null) {
				this.cleanOpRead();
				handleInputClose();
				// }
				return;
			}
			assert LOG.assertTrace(" read from os buffer bytes size is ", rc);
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
				this.cleanOpRead();
				handleInputClose();
			}
		}
	}

	// protected void handleWriteException(IOException e, InputBuf buf,
	// AsyncTask task) {
	// if (task != null)
	// task.failed(e, executor);
	// this.closeJavaChannel();
	// }

	protected abstract void handleInputClose();

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
			key.interestOps(interestOps & ~SelectionKey.OP_READ);
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
			key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
		}
	}

	protected void writeData(byte[] buf, int index, int len) {
		assert buf != null;
		assert index >= 0;
		assert len > 0;
		assert buf.length >= (index + len);
		if (null == this.cacheBuffer) {
			this.cacheBuffer = ByteBuffer.allocate(8192);
		} else {
			if (!cacheBuffer.hasRemaining()) {
				cacheBuffer.flip();
				this.write(this.cacheBuffer);
				cacheBuffer = ByteBuffer.allocate(8192);
			}
		}
		while (len > 0) {
			int wl = Integer.min(cacheBuffer.remaining(), len);
			cacheBuffer.put(buf, index, wl);
			index += wl;
			len -= wl;
			if (len > 0) {
				cacheBuffer.flip();
				this.write(this.cacheBuffer);
				cacheBuffer = ByteBuffer.allocate(8192);
			}
		}
	}

	protected void writeByteData(byte value) {
		if (null == this.cacheBuffer) {
			this.cacheBuffer = ByteBuffer.allocate(8192);
		} else {
			if (!cacheBuffer.hasRemaining()) {
				cacheBuffer.flip();
				this.write(this.cacheBuffer);
				cacheBuffer = ByteBuffer.allocate(8192);
			}
		}
		this.cacheBuffer.put(value);
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
					this.write(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			while (len > 0) {
				int wl = Integer.min(cacheBuffer.remaining(), len);
				cacheBuffer.put(buf, index, wl);
				index += wl;
				len -= wl;
				if (len > 0) {
					cacheBuffer.flip();
					this.write(cacheBuffer);
					cacheBuffer = ByteBuffer.allocate(8192);
				}
			}
			this.cacheBuffer.flip();
			this.write(this.cacheBuffer, task);
			this.cacheBuffer = null;
		} else {
			task.failed(this.writeException, executor);
		}
	}

	protected void flushData(TaskCompletionHandler task) {
		assert task != null;
		if (this.javaChannel != null) {
			if (null == this.cacheBuffer) {
				this.write(EMPTY_BUFFER, task);
			} else {
				this.cacheBuffer.flip();
				this.write(this.cacheBuffer, task);
				this.cacheBuffer = null;
			}
		} else {
			executor.safeInvokeFailed(task, this.writeException);
		}
	}

	protected void write(ByteBuffer buffer) {
		if (this.writeException == null) {
			if (outputCache.isEmpty()) {
				if (buffer.hasRemaining()) {
					try {
						this.javaChannel.write(buffer);
					} catch (IOException e) {
						if (LOG.enableWarn()) {
							LOG.warn(this.channelId + " write data error", e);
						}
						this.writeException = e;
						this.close();
						return;
					}
					if (buffer.hasRemaining()) {
						outputCache.offer(buffer);
						this.setOpWrite();
					}
				}
			} else {
				outputCache.offer(buffer);
			}
		}
	}

	protected void write(ByteBuffer buffer, TaskCompletionHandler task) {
		if (this.writeException == null) {
			if (outputCache.isEmpty()) {
				if (buffer.hasRemaining()) {
					try {
						this.javaChannel.write(buffer);
					} catch (IOException e) {
						if (LOG.enableWarn()) {
							LOG.warn(this.channelId + " write data error", e);
						}
						this.writeException = e;
						this.outputCache.offer(EMPTY_BUFFER, task);
						this.close();
						return;
					}
					if (buffer.hasRemaining()) {
						outputCache.offer(buffer, task);
						this.setOpWrite();
					} else {
						executor.safeInvokeCompleted(task);
					}
				} else {
					executor.safeInvokeCompleted(task);
				}
			} else {
				outputCache.offer(buffer, task);
			}
		} else {
			executor.safeInvokeFailed(task, this.writeException);
		}
	}

	@Override
	public void write() {
		assert LOG.assertDebug(this.channelId," invoke write()");
		for (;;) {
			TagNode tagNode = outputCache.peekTagNode();
			if (tagNode == null) {
				if (this.key != null) {
					this.cleanOpWrite();
				}
				return;
			}
			ByteBuffer buf = tagNode.item();
			TaskCompletionHandler task = tagNode.tag();
			if (buf.hasRemaining()) {
				try {
					this.javaChannel.write(buf);
				} catch (IOException e) {
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + " javaChannel.write data error", e);
					}
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
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

	protected final TagQueueHandler<ByteBuffer, TaskCompletionHandler> WRITE_ERROR_HANDLER = new TagQueueHandler<ByteBuffer, TaskCompletionHandler>() {
		@Override
		public void process(ByteBuffer item, TaskCompletionHandler tag) {
			if (tag != null) {
				executor.safeInvokeFailed(tag, writeException);
			}
		}
	};
}
