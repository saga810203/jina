package org.jfw.jina.http2.impl;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http2.FrameWriter;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Connection;
import org.jfw.jina.http2.Http2FlagsUtil;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.impl.QueueProviderImpl.LinkedQueue;

public abstract class Http2FrameReader implements Http2Connection,FrameWriter,KeepAliveCheck{
	public static final long INVALID_STREAM_ID = Long.MIN_VALUE;
	
	public static final int FRAME_CONFIG_HEADER_SIZE = 9;
	public static final int FRAME_PRIORITY_PAYLOAD_LENGTH = 5;
	public static final int FRAME_RSTSTREAM_PAYLOAD_LENGTH = 4;
	public static final int FRAME_SETTING_SETTING_ENTRY_LENGTH = 6;
	public static final int FRAME_PING_PAYLOAD_LENGTH = 8;
	
	
	
	
	public static final byte FRAME_STATE_READ_HEADER = 10;
	public static final byte FRAME_STATE_READ_DATA = 20;

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
	
	
	protected Http2AsyncExecutor executor;
	
	protected int frameConfigMaxSize = 0x4000;

	protected byte[] frameHeaderBuffer = new byte[9];
	protected int frameHeaderIndex = 0;

	protected int payloadLength = 0;
	protected byte frameType = 0;
	protected byte frameFlag = 0;
	protected int streamId = 0;

	protected Queue framePayload;
	protected int payloadIndex = 0;

	protected Queue headersPayload;
	protected Queue dataPayload;
	// protected boolean headerProcessing = false;
	protected long streamIdOfHeaders = INVALID_STREAM_ID;

	private byte currentState = FRAME_STATE_READ_HEADER;
	
	private Stream lastCreateStream = null;
	private int lastCreateStreamId = -1;
	
	
	
	
	protected abstract void handleInputClose();
	@Override
	public abstract void keepAliveTimeout() ;
	protected abstract void handleProtocolError();
	
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
				if(this.payloadLength<10){
					buf.readBytes(this.frameHeaderBuffer, this.payloadIndex,readSize);
				}else{
					this.framePayload.offer(buf.duplicate(readSize));
					buf.skipBytes(readSize);
				}
			} else {
				if(this.payloadLength<10){
					buf.readBytes(this.frameHeaderBuffer, this.payloadIndex,readSize);
				}else{
					this.framePayload.offer(buf.slice());
					buf.skipAllBytes();
				}
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
	protected void handleWindowUpdateFrame() {
		this.readCacheBytes(4);
		int windowSizeIncrement = (((frameHeaderBuffer[0] & 0x7f) << 24) | ((frameHeaderBuffer[1] & 0xff) << 16) | ((frameHeaderBuffer[2] & 0xff) << 8)
				| (frameHeaderBuffer[3] & 0xff));
		assert this.framePayload.isEmpty();
		if(windowSizeIncrement ==0){
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_WINDOW_UPDATE;
		}
		// if (windowSizeIncrement == 0) {
		// throw streamError(streamId, PROTOCOL_ERROR,
		// "Received WINDOW_UPDATE with delta 0 for stream: %d", streamId);
		// }
		if(this.streamId==0){
			incWindowSize(windowSizeIncrement);
		}else{
			Stream stream = stream(this.streamId);
			if(stream!=null){
				stream.incWindowSize(windowSizeIncrement);
			}
		}
	}
	protected void handleDataFrame() {
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		int oldPayloadSize = payloadLength;
		int padding = 0;
		if (hasPadding) {
			padding = this.readUByteInPL();
			--payloadLength;
		}
		if (payloadLength < padding) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
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
		this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
		incWindowSize(oldPayloadSize);
		Stream cStream= this.lastCreateStream;
		if(this.lastCreateStreamId != this.streamId){
			cStream = openedStream(this.streamId);
		}
		cStream.incWindowSize(oldPayloadSize);
		cStream.hanldData(Http2FlagsUtil.endOfStream(frameFlag));
		//this.dataPayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
		assert this.dataPayload.isEmpty();
	}
	protected void handleContinuationFrame() {
		if (this.payloadLength > 0) {
			this.framePayload.offer(this.headersPayload);
		}
		if (Http2FlagsUtil.endOfHeaders(frameFlag)) {
			this.lastCreateStreamId = this.streamId;
			if (priorityInHeaders) {
				this.lastCreateStream = createStream(streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
			} else {
				this.lastCreateStream =createStream(endOfStreamInHeaders);
			}
			// this.headersPayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			assert this.headersPayload.isEmpty();
			this.streamIdOfHeaders = INVALID_STREAM_ID;
		}
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
			this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
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
				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			} else {
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
			}
			if (endOfHeaders) {
				this.lastCreateStreamId = this.streamId;
				this.lastCreateStream =createStream(streamDependency, weightInHeaders, exclusiveInHeaders, this.endOfStreamInHeaders);				
				// this.headersPayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
				assert this.headersPayload.isEmpty();
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		} else {
			priorityInHeaders = false;
			if (padding != 0) {
				int rpll = payloadLength - padding;
				if (rpll > 0)
					this.slicePayload(rpll, this.headersPayload);

				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			} else {
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			}
			if (endOfHeaders) {
				this.lastCreateStreamId = this.streamId;
				this.lastCreateStream = createStream(this.endOfStreamInHeaders);				
				// this.headersPayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
				assert this.headersPayload.isEmpty();
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		}
	}
	
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
	private short readUByteInPL() {
		assert !framePayload.isEmpty();
		InputBuf buf = (InputBuf) framePayload.unsafePeek();
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
				framePayload.unsafeShift();
				buf = (InputBuf) framePayload.unsafePeek();
				assert buf != null;
				assert buf.readable();
			} else {
				break;
			}
		}
		if (!buf.readable()) {
			buf.release();
			framePayload.unsafeShift();
		}
	}
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


}
