package org.jfw.jina.http2;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Comparator;

import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.TagQueue.TagNode;
import org.jfw.jina.util.TagQueue.TagQueueHandler;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.impl.QueueProviderImpl.LinkedQueue;

public abstract class Http2FrameChannel implements NioAsyncChannel, KeepAliveCheck, Http2FrameListener {

	protected Http2FrameChannel(Http2AsyncExecutor executor, SocketChannel javaChannel) {

		this.executor = executor;
		this.javaChannel = javaChannel;
		this.framePayload = executor.newQueue();
		this.headersPayload = executor.newQueue();
		this.dataPayload = executor.newQueue();
		this.outputCache = executor.newTagQueue();
	}

	public static final int FRAME_CONFIG_HEADER_SIZE = 9;

	public static final int FRAME_PRIORITY_PAYLOAD_LENGTH = 5;
	public static final int FRAME_RSTSTREAM_PAYLOAD_LENGTH = 4;
	public static final int FRAME_SETTING_SETTING_ENTRY_LENGTH = 6;
	public static final int FRAME_PING_PAYLOAD_LENGTH = 8;

	private static final byte FRAME_STATE_READ_HEADER = 10;
	private static final byte FRAME_STATE_READ_DATA = 20;

	public static final byte FRAME_TYPE_DATA = 0x0;
	public static final byte FRAME_TYPE_HEADERS = 0x1;
	public static final byte FRAME_TYPE_PRIORITY = 0x2;
	public static final byte FRAME_TYPE_RST_STREAM = 0x3;
	public static final byte FRAME_TYPE_SETTINGS = 0x4;
	public static final byte FRAME_TYPE_PUSH_PROMISE = 0x5;
	public static final byte FRAME_TYPE_PING = 0x6;
	public static final byte FRAME_TYPE_GO_AWAY = 0x7;
	public static final byte FRAME_TYPE_WINDOW_UPDATE = 0x8;
	public static final byte FRAME_TYPE_CONTINUATION = 0x9;

	public static final int DEFAULT_MAX_FRAME_SIZE = 0x4000;

	public static final long INVALID_STREAM_ID = Long.MIN_VALUE;

	protected Http2AsyncExecutor executor;
	protected SocketChannel javaChannel;
	protected SelectionKey key;
	protected final TagQueue outputCache;
	protected Throwable writeException;

	protected int frameConfigMaxSize = 0x4000;

	protected byte[] frameHeaderBuffer = new byte[9];
	protected int frameHeaderIndex = 0;

	protected int payloadLength = 0;
	protected byte frameType = 0;
	protected byte frameFlag = 0;
	protected long streamId = 0;

	protected Queue framePayload;
	protected int payloadIndex = 0;

	protected Queue headersPayload;
	protected Queue dataPayload;
	// protected boolean headerProcessing = false;
	protected long streamIdOfHeaders = INVALID_STREAM_ID;

	private byte currentState = FRAME_STATE_READ_HEADER;

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ, this);
		this.afterRegister();
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

	protected final TagQueueHandler WRITE_ERROR_HANDLER = new TagQueueHandler() {
		@Override
		public void process(Object item, Object tag) {
			((InputBuf) item).release();
			if (tag != null) {
				((TaskCompletionHandler) tag).failed(writeException, executor);
			}
		}
	};

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

	@Override
	public void write() {
		TagNode tagNode = null;
		while ((tagNode = outputCache.peekTagNode()) != null) {
			OutputFrame frame = (OutputFrame) tagNode.item();
			TaskCompletionHandler task = (TaskCompletionHandler) tagNode.tag();
			boolean flushOk = false;
			try {
				flushOk = frame.flush(this.javaChannel);
			} catch (IOException e) {
				this.writeException = e;
				this.close();
				return;
			}
			if (!flushOk) {
				return;
			} else {
				executor.freeOutputFrame(frame);
				outputCache.unsafeShift();
				if (task != null) {
					task.completed(executor);
				}
			}
		}
		this.cleanOpWrite();
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		assert key.attachment() == this;
		this.key = key;
	}

	protected final void cleanOpRead() {
		assert this.key != null && this.key.isValid();
		final int interestOps = key.interestOps();
		if ((interestOps & SelectionKey.OP_READ) != 0) {
			key.interestOps(interestOps | ~SelectionKey.OP_READ);
		}
	}

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
				this.close();
				return;
			} finally {
				buf.release();
			}
		}
	}

	public final void write(OutputFrame frame) {
		assert frame.type() != FRAME_TYPE_PING;
		this.outputCache.offer(frame);
		this.setOpWrite();
	}

	public final void write(OutputFrame frame, TaskCompletionHandler task) {
		assert frame.type() != FRAME_TYPE_PING;
		assert task != null;
		this.outputCache.offer(frame, task);
		this.setOpWrite();
	}

	protected void handleInputClose() {
		// TODO Auto-generated method stub

	}

	protected void handleProtocolError() {

	}

	protected void handleRead(InputBuf buf, int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
			for (;;) {
				if (this.currentState == FRAME_STATE_READ_HEADER) {
					if (!doReadFrameHeader(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (this.currentState == FRAME_STATE_READ_DATA) {
					if (!doReadFramePayLoad(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (this.currentState < 0) {
					this.handleProtocolError();
					return;
				}
			}
		} else {
			handleInputClose();
		}
	}

	private boolean doReadFramePayLoad(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(this.payloadLength - this.payloadIndex, nr);
		if (readSize > 0) {
			if (nr > readSize) {
				this.framePayload.offer(buf.duplicate(readSize));
				buf.skipBytes(readSize);
			} else {
				this.framePayload.offer(buf.slice());
				buf.skipAllBytes();
			}
			this.payloadIndex += readSize;
		}
		if (this.payloadIndex == this.payloadLength) {
			this.payloadIndex = 0;
			this.currentState = FRAME_STATE_READ_HEADER;
			if (frameType == FRAME_TYPE_DATA) {
				handleDataFrame();
			} else if (frameType == FRAME_TYPE_HEADERS) {
				handleHeadersFrame();
			} else if (frameType == FRAME_TYPE_PRIORITY) {
				handlePriorityFrame();
			} else if (frameType == FRAME_TYPE_RST_STREAM) {
				handleRstStreamFrame();
			} else if (frameType == FRAME_TYPE_SETTINGS) {
				handleSettingsFrame();
			} else if (frameType == FRAME_TYPE_PUSH_PROMISE) {
				handlePushPromiseFrame();
			} else if (frameType == FRAME_TYPE_PING) {
				handlePingFrame();
			} else if (frameType == FRAME_TYPE_GO_AWAY) {
				handleGoAwayFrame();
			} else if (frameType == FRAME_TYPE_WINDOW_UPDATE) {
				handleWindowUpdateFrame();
			} else if (frameType == FRAME_TYPE_CONTINUATION) {
				handleContinuationFrame();
			} else {
				handleUnknownFrame();
			}

			return true;
		} else {
			return false;
		}
	}

	protected abstract void handlePingFrame();

	private void slicePayload(int length, Queue dest) {
		for (;;) {
			InputBuf buf = (InputBuf) this.framePayload.poll();
			assert buf != null;
			int nr = buf.readableBytes();
			if (length >= nr) {
				dest.offer(buf);
				length -= nr;
				if (length == 0) {
					return;
				}
			} else {
				dest.offer(buf.duplicate(length));
				buf.release();
				return;
			}
		}
	}

	protected void handleDataFrame() {
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		int padding = 0;
		if (hasPadding) {
			padding = this.readUByteInPL();
			--payloadLength;
		}
		if (payloadLength < padding) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			this.framePayload.clear(RELEASE_INPUT_BUF);
			return;
		}
		if (padding != 0) {
			int rpll = payloadLength - padding;
			if (rpll > 0)
				this.slicePayload(rpll, this.dataPayload);
		} else {
			if (payloadLength > 0) {
				this.framePayload.offerTo(this.dataPayload);
			}
		}
		this.framePayload.clear(RELEASE_INPUT_BUF);
		onDataRead(Http2FlagsUtil.endOfStream(frameFlag));
		this.dataPayload.clear(RELEASE_INPUT_BUF);
	}

	private boolean priorityInHeaders = false;
	private int streamDependency = 0;
	private short weightInHeaders = 0;
	private boolean exclusiveInHeaders = false;
	private boolean endOfStreamInHeaders = false;

	protected void handleHeadersFrame() {
		this.streamIdOfHeaders = this.streamId;
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		boolean endOfHeaders = Http2FlagsUtil.endOfHeaders(frameFlag);
		this.endOfStreamInHeaders = Http2FlagsUtil.endOfStream(frameType);
		int padding = 0;
		if (hasPadding) {
			padding = this.readUByteInPL();
			--payloadLength;
		}

		if (payloadLength < padding) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			this.framePayload.clear(RELEASE_INPUT_BUF);
			return;
		}

		if (Http2FlagsUtil.priorityPresent(frameFlag)) {
			priorityInHeaders = true;
			this.readCacheBytes(4);
			long word1 = (((this.frameHeaderBuffer[0] & 0xff) << 24) | ((this.frameHeaderBuffer[2] & 0xff) << 16) | ((this.frameHeaderBuffer[3] & 0xff) << 8)
					| (this.frameHeaderBuffer[4] & 0xff)) & 0xFFFFFFFFL;
			payloadLength -= 4;

			exclusiveInHeaders = (word1 & 0x80000000L) != 0;
			streamDependency = (int) (word1 & 0x7FFFFFFFL);
			if (streamDependency == streamId) {
				// TODO impl
				// throw streamError(streamId, PROTOCOL_ERROR, "A stream cannot
				// depend on itself.");
			}
			weightInHeaders = (short) (this.readUByteInPL() + 1);
			--payloadLength;

			if (padding != 0) {
				int rpll = payloadLength - padding;
				if (rpll > 0)
					this.slicePayload(rpll, this.headersPayload);
				this.framePayload.clear(RELEASE_INPUT_BUF);
			} else {
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
			}
			if (endOfHeaders) {
				onHeadersRead(streamDependency, weightInHeaders, exclusiveInHeaders, this.endOfStreamInHeaders);
				this.headersPayload.clear(RELEASE_INPUT_BUF);
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		} else {
			priorityInHeaders = false;
			if (padding != 0) {
				int rpll = payloadLength - padding;
				if (rpll > 0)
					this.slicePayload(rpll, this.headersPayload);

				this.framePayload.clear(RELEASE_INPUT_BUF);
			} else {
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
				this.framePayload.clear(RELEASE_INPUT_BUF);
			}
			if (endOfHeaders) {
				onHeadersRead(this.endOfStreamInHeaders);
				this.headersPayload.clear(RELEASE_INPUT_BUF);
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		}
	}

	protected void handlePriorityFrame() {
		this.readCacheBytes(4);
		long word1 = (((this.frameHeaderBuffer[0] & 0xff) << 24) | ((this.frameHeaderBuffer[2] & 0xff) << 16) | ((this.frameHeaderBuffer[3] & 0xff) << 8)
				| (this.frameHeaderBuffer[4] & 0xff)) & 0xFFFFFFFFL;
		;
		payloadLength -= 4;

		final boolean exclusive = (word1 & 0x80000000L) != 0;
		final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
		if (streamDependency == streamId) {
			// TODO impl throw streamError(streamId, PROTOCOL_ERROR, "A stream
			// cannot depend on itself.");
		}
		final short weight = (short) (this.readUByteInPL() + 1);
		assert this.framePayload.isEmpty();
		onPriorityRead(streamDependency, weight, exclusive);
	}

	protected void handleRstStreamFrame() {
		this.readCacheBytes(4);
		long errorCode = (((frameHeaderBuffer[0] & 0xff) << 24) | ((frameHeaderBuffer[1] & 0xff) << 16) | ((frameHeaderBuffer[2] & 0xff) << 8)
				| (frameHeaderBuffer[3] & 0xff)) & 0xFFFFFFFFL;
		assert this.framePayload.isEmpty();
		onRstStreamRead(errorCode);
	}

	protected void handleSettingsFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			onSettingsAckRead();
		} else {
			int numSettings = payloadLength / FRAME_SETTING_SETTING_ENTRY_LENGTH;
			Http2Settings settings = new Http2Settings();
			for (int index = 0; index < numSettings; ++index) {
				this.readCacheBytes(FRAME_SETTING_SETTING_ENTRY_LENGTH);

				char id = (char) (((frameHeaderBuffer[0] << 8) | (frameHeaderBuffer[1] & 0xFF)) & 0xffff);
				long value = 0xffffffffL & (((frameHeaderBuffer[2] & 0xff) << 24) | ((frameHeaderBuffer[3] & 0xff) << 16) | ((frameHeaderBuffer[4] & 0xff) << 8)
						| (frameHeaderBuffer[5] & 0xff));
				try {
					if (id == Http2Settings.SETTINGS_HEADER_TABLE_SIZE) {
						settings.headerTableSize(value);
					} else if (id == Http2Settings.SETTINGS_ENABLE_PUSH) {
						if (value != 0L && value != 1L) {
							throw new IllegalArgumentException("Setting ENABLE_PUSH is invalid: " + value);
						}
						settings.pushEnabled(1 == value);
					} else if (id == Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS) {
						settings.maxConcurrentStreams(value);
					} else if (id == Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE) {
						settings.initialWindowSize(value);
					} else if (id == Http2Settings.SETTINGS_MAX_FRAME_SIZE) {
						settings.maxFrameSize(value);
					} else if (id == Http2Settings.SETTINGS_MAX_HEADER_LIST_SIZE) {
						settings.maxHeaderListSize(value);
					}
					;
				} catch (IllegalArgumentException e) {
					// TODO
					this.currentState = Http2ProtocolError.ERROR_INVALID_SETTING_VALUE;
					this.framePayload.clear(RELEASE_INPUT_BUF);
					return;
				}
			}
			onSettingsRead(settings);
		}
	}

	protected abstract void handlePushPromiseFrame();



	protected void handleGoAwayFrame() {
		this.readCacheBytes(8);
		int lastStreamId = (((frameHeaderBuffer[0] & 0x7f) << 24) | ((frameHeaderBuffer[1] & 0xff) << 16) | ((frameHeaderBuffer[2] & 0xff) << 8)
				| (frameHeaderBuffer[3] & 0xff));
		long errorCode = (((frameHeaderBuffer[4] & 0xff) << 24) | ((frameHeaderBuffer[5] & 0xff) << 16) | ((frameHeaderBuffer[6] & 0xff) << 8)
				| (frameHeaderBuffer[7] & 0xff)) & 0xFFFFFFFFL;
		onGoAwayRead(lastStreamId, errorCode);
		this.framePayload.clear(RELEASE_INPUT_BUF);
	}

	protected void handleWindowUpdateFrame() {
		this.readCacheBytes(4);
		int windowSizeIncrement = (((frameHeaderBuffer[0] & 0x7f) << 24) | ((frameHeaderBuffer[1] & 0xff) << 16) | ((frameHeaderBuffer[2] & 0xff) << 8)
				| (frameHeaderBuffer[3] & 0xff));
		// if (windowSizeIncrement == 0) {
		// throw streamError(streamId, PROTOCOL_ERROR,
		// "Received WINDOW_UPDATE with delta 0 for stream: %d", streamId);
		// }
		onWindowUpdateRead(windowSizeIncrement);

	}

	protected void handleContinuationFrame() {
		if (this.payloadLength > 0) {
			this.framePayload.offer(this.headersPayload);
		}
		if (Http2FlagsUtil.endOfHeaders(frameFlag)) {
			if (priorityInHeaders) {
				onHeadersRead(streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
			} else {
				onHeadersRead(endOfStreamInHeaders);
			}
			this.headersPayload.clear(RELEASE_INPUT_BUF);
			this.streamIdOfHeaders = INVALID_STREAM_ID;
		}
	}

	protected abstract void handleUnknownFrame();

	private boolean doReadFrameHeader(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		buf.readBytes(this.frameHeaderBuffer, this.frameHeaderIndex, readSize);
		this.frameHeaderIndex += readSize;
		if (this.frameHeaderIndex == FRAME_CONFIG_HEADER_SIZE) {
			this.frameHeaderIndex = 0;

			this.payloadLength = (this.frameHeaderBuffer[0] & 0xff) << 16 | ((frameHeaderBuffer[1] & 0xff) << 8) | (frameHeaderBuffer[2] & 0xff);
			if (payloadLength > this.frameConfigMaxSize) {
				this.currentState = Http2ProtocolError.ERROR_MAX_FRAME_SIZE;
				return true;
			}
			frameType = this.frameHeaderBuffer[3];
			this.frameFlag = this.frameHeaderBuffer[4];
			streamId = ((frameHeaderBuffer[5] & 0x7f) << 24 | (frameHeaderBuffer[6] & 0xff) << 16 | (frameHeaderBuffer[7] & 0xff) << 8
					| frameHeaderBuffer[8] & 0xff);

			this.currentState = FRAME_STATE_READ_DATA;
			if (frameType == FRAME_TYPE_DATA)
				verifyDataFrame();
			else if (frameType == FRAME_TYPE_HEADERS)
				verifyHeadersFrame();
			else if (frameType == FRAME_TYPE_PRIORITY)
				verifyPriorityFrame();
			else if (frameType == FRAME_TYPE_RST_STREAM)
				verifyRstStreamFrame();
			else if (frameType == FRAME_TYPE_SETTINGS)
				verifySettingsFrame();
			else if (frameType == FRAME_TYPE_PUSH_PROMISE)
				verifyPushPromiseFrame();
			else if (frameType == FRAME_TYPE_PING)
				verifyPingFrame();
			else if (frameType == FRAME_TYPE_GO_AWAY)
				verifyGoAwayFrame();
			else if (frameType == FRAME_TYPE_WINDOW_UPDATE)
				verifyWindowUpdateFrame();
			else if (frameType == FRAME_TYPE_CONTINUATION)
				verifyContinuationFrame();
			else
				verifyUnknownFrame();

			return true;
		}
		return false;

	}

	private void verifyUnknownFrame() {
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifyContinuationFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}

		if (this.streamIdOfHeaders == INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION_NOT;
			return;
		}

		if (streamId != this.streamIdOfHeaders) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_STREAM_ID_WITH_CONTINUATION;
			return;
		}

		if (payloadLength < 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}
	}

	private void verifyWindowUpdateFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != 4) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE;
			return;
		}

	}

	private void verifyGoAwayFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength < 8) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_GOAWAY;
			return;
		}

	}

	private void verifyPingFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength != FRAME_PING_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PING;
			return;
		}

	}

	private void verifyPushPromiseFrame() {
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifySettingsFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// //IGNORE
		// }
		if (Http2FlagsUtil.ack(frameFlag) && payloadLength > 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING_ACK;
			return;
		}
		if (payloadLength % FRAME_SETTING_SETTING_ENTRY_LENGTH > 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING;
			return;
		}

	}

	private void verifyRstStreamFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_RSTSTREAM_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_RSTSTREAM;
			return;
		}
	}

	private void verifyPriorityFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_PRIORITY_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PRIORITY;
			return;
		}
	}

	private void verifyHeadersFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < (Http2FlagsUtil.getNumPriorityBytes(this.frameFlag)) + Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	private void verifyDataFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	// at first read in payload
	private int readPadding() {
		if (Http2FlagsUtil.paddingPresent(this.frameFlag)) {
			return readUByteInPL() + 1;
		} else {
			return 0;
		}
	}

	private short readUByteInPL() {
		assert !framePayload.isEmpty();
		InputBuf buf = (InputBuf) (((LinkedQueue) framePayload).head.next.item);
		assert buf.readable();
		short ret = buf.readUnsignedByte();
		if (!buf.readable()) {
			buf.release();
			framePayload.unsafeShift();
		}
		return ret;
	}

	protected void readCacheBytes(int len) {
		assert len <= 9 && len > 0;
		assert this.frameHeaderIndex == 0;
		assert !framePayload.isEmpty();
		InputBuf buf = (InputBuf) framePayload.unsafePeek();
		assert buf != null;
		assert buf.readable();
		int ridx = 0;
		for (;;) {
			int rs = Integer.max(len, buf.readableBytes());
			buf.readBytes(this.frameHeaderBuffer, ridx, rs);
			len -= rs;
			if (len != 0) {
				framePayload.unsafeShift();
				ridx += rs;
				buf.release();
				buf = (InputBuf) framePayload.unsafePeek();
				assert buf != null;
				assert buf.readable();
			} else {
				break;
			}
		}
		if (!buf.readable()) {
			framePayload.unsafeShift();
		}
	}


	protected void afterRegister() {
		this.keepAliveNode = this.executor.newDNode(this);
		this.addKeepAliveCheck();
	}

	private DNode keepAliveNode;
	private long keepAliveTimeout = Long.MAX_VALUE;

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue();
			return true;
		}
		return false;
	}

	public boolean addKeepAliveCheck() {
		if (this.keepAliveTimeout == Long.MAX_VALUE) {
			this.keepAliveTimeout = System.currentTimeMillis();
			this.keepAliveNode.enqueue(this.executor.getKeepAliveQueue());
			return true;
		}
		return false;
	}

	@Override
	public long getKeepAliveTime() {
		return this.keepAliveTimeout;
	}

	@Override
	public void keepAliveTimeout() {
		this.close();
	}

}
