package org.jfw.jina.http2.impl;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.impl.AbstractNioAsyncChannel;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http2.FrameWriter;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Connection;
import org.jfw.jina.http2.Http2FlagsUtil;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.Huffman;
import org.jfw.jina.http2.headers.HpackDynamicTable;
import org.jfw.jina.http2.headers.HpackHeaderField;
import org.jfw.jina.http2.headers.HpackStaticTable;
import org.jfw.jina.http2.headers.HpackUtil;
import org.jfw.jina.log.LogFactory;
import org.jfw.jina.log.Logger;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.StringUtil;

public abstract class Http2FrameReader<T extends Http2AsyncExecutor> extends AbstractNioAsyncChannel<T>
		implements Http2Connection, FrameWriter, KeepAliveCheck {
	private static final Logger LOG = LogFactory.getLog(Http2FrameReader.class);

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

	private static final byte READ_HEADER_REPRESENTATION = 0;
	private static final byte READ_MAX_DYNAMIC_TABLE_SIZE = 1;
	private static final byte READ_INDEXED_HEADER = 2;
	private static final byte READ_INDEXED_HEADER_NAME = 3;
	private static final byte READ_LITERAL_HEADER_NAME_LENGTH_PREFIX = 4;
	private static final byte READ_LITERAL_HEADER_NAME_LENGTH = 5;
	private static final byte READ_LITERAL_HEADER_NAME = 6;
	private static final byte READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX = 7;
	private static final byte READ_LITERAL_HEADER_VALUE_LENGTH = 8;
	private static final byte READ_LITERAL_HEADER_VALUE = 9;
	// local Setting

	boolean localEnablePush = false;
	long localMaxConcurrentStreams = Long.MAX_VALUE;
	int localInitialWindowSize = 65535;
	int localMaxFrameSize = 16777215;

	int localConnectionInitialWinodwSize = 65535;
	// remote Setting
	long remoteHeaderTableSize = 4096;
	// this (MAX_HEADER_LIST_SIZE) unknow so don't check in recv , but send use
	// remote setting
	long maxHeaderListSize = Long.MAX_VALUE;
	// int recvWindowSize = 65535;
	HpackDynamicTable remoteDynaTable = new HpackDynamicTable(4096);
	final byte[] frameReadBuffer;
	int frameHeaderIndex = 0;

	int payloadLength = 0;
	byte frameType = 0;
	byte frameFlag = 0;
	int streamId = 0;
	int payloadIndex = 0;
	CacheStream cachePayload;
	int activeStreams = 0;

	// protected boolean headerProcessing = false;
	long streamIdOfHeaders = INVALID_STREAM_ID;

	byte currentState = FRAME_STATE_READ_HEADER;

	boolean goAwayed = false;

	protected Http2FrameReader(T executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
		this.cachePayload = new CacheStream();
		this.frameReadBuffer = new byte[64];
		this.keepAliveNode = executor.newDNode(this);
	}

	protected abstract void handleInputClose();

	protected abstract void handleProtocolError();

	public void handleRead(int len) {
		this.removeKeepAliveCheck();
		while (this.widx > this.ridx) {
			if (this.currentState == FRAME_STATE_READ_HEADER) {
				if (!doReadFrameHeader()) {
					this.addKeepAliveCheck();
					this.compactReadBuffer();
					return;
				}
			}
			if (this.currentState == FRAME_STATE_READ_DATA) {
				if (!doReadFramePayLoad()) {
					this.compactReadBuffer();
					this.addKeepAliveCheck();
					return;
				}
			}
			if (this.currentState < 0) {
				this.handleProtocolError();
				return;
			}
		}
		if (this.activeStreams == 0) {
			this.addKeepAliveCheck();
		}
	}

	private boolean doReadFramePayLoad() {
		int nr = this.widx - this.ridx;
		int readSize = Integer.min(this.payloadLength - this.payloadIndex, nr);

		if (readSize > 0) {
			if (nr > readSize) {
				this.cachePayload.write(this.rBytes, this.ridx, readSize);
			} else {
				this.cachePayload.write(this.rBytes, this.ridx, readSize);
			}
			this.ridx += readSize;
			this.payloadIndex += readSize;
		}
		if (this.payloadIndex == this.payloadLength) {
			this.payloadIndex = 0;
			this.currentState = FRAME_STATE_READ_HEADER;
			assert LOG.debug(this.channelId + " start handle frame payload");
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
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " ignore promis frame");
				}
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

	private void handleGoAwayFrame() {
		byte[] buffer = cachePayload.buffer;
		int lastStreamId = ((buffer[0] & 0x7f) << 24 | (buffer[1] & 0xff) << 16 | (buffer[2] & 0xff) << 8 | buffer[3] & 0xff);
		long errorCode = ((buffer[4] & 0xff) << 24 | (buffer[5] & 0xff) << 16 | (buffer[6] & 0xff) << 8 | buffer[7] & 0xff) & 0xFFFFFFFFL;
		assert LOG.debug(buffer, 8, this.payloadLength);
		assert LOG.debug(this.channelId + " call goAway(" + lastStreamId + "::int," + errorCode + "::long)");
		goAway(lastStreamId, errorCode);
	}

	private void handleRstStreamFrame() {
		byte[] buffer = cachePayload.buffer;
		long errorCode = ((buffer[0] & 0xff) << 24 | (buffer[1] & 0xff) << 16 | (buffer[2] & 0xff) << 8 | buffer[3] & 0xff) & 0xFFFFFFFFL;
		assert LOG.debug(this.channelId + " call resetStream(" + errorCode + "::long)");
		resetStream(errorCode);
	}

	private void handleUnknownFrame() {
		if (LOG.enableWarn()) {
			LOG.warn(this.channelId + " ignore unknown frame payload");
		}
	}

	protected void handlePingFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			recvPingAck(this.cachePayload.buffer);
		} else {
			writePingAck(this.cachePayload.buffer);
		}
	}

	protected void handleSettingsFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			recvSettingAck();
		} else {
			byte[] buffer = cachePayload.buffer;
			int numSettings = payloadLength / FRAME_SETTING_SETTING_ENTRY_LENGTH;
			Http2Settings settings = new Http2Settings();
			for (int index = 0, setIdx = 0; index < numSettings; ++index) {
				char id = (char) (((buffer[setIdx++] << 8) | (buffer[setIdx++] & 0xFF)) & 0xffff);
				long value = 0xffffffffL & (((buffer[setIdx++] & 0xff) << 24) | ((buffer[setIdx++] & 0xff) << 16) | ((buffer[setIdx++] & 0xff) << 8)
						| (buffer[setIdx++] & 0xff));
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
				} catch (IllegalArgumentException e) {
					this.currentState = Http2ProtocolError.ERROR_INVALID_SETTING_VALUE;
					if (LOG.enableWarn()) {
						LOG.warn(this.channelId + "parse http2 SETTING error", e);
					}
					return;
				}
			}
			assert LOG.debug(this.channelId + " parse http2 SETTING success:" + settings.toString());
			applySetting(settings);
			writeSettingAck();
		}
	}

	protected void handlePriorityFrame() {
		byte[] buffer = this.cachePayload.buffer;
		long word1 = (((buffer[0] & 0xff) << 24) | ((buffer[2] & 0xff) << 16) | ((buffer[3] & 0xff) << 8) | (buffer[4] & 0xff)) & 0xFFFFFFFFL;

		final boolean exclusive = (word1 & 0x80000000L) != 0;
		final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
		if (streamDependency == streamId) {
			// TODO impl throw streamError(streamId, PROTOCOL_ERROR, "A stream
			// cannot depend on itself.");
		}
		final short weight = (short) ((buffer[4] & 0xFF) + 1);
		assert LOG.debug(this.channelId + " call handlePriority(" + streamDependency + "::int," + weight + "::short," + exclusive + "::boolean)");
		handlePriority(streamDependency, weight, exclusive);
	}

	protected void handleWindowUpdateFrame() {
		byte[] buffer = this.cachePayload.buffer;
		int windowSizeIncrement = (((buffer[0] & 0x7f) << 24) | ((buffer[1] & 0xff) << 16) | ((buffer[2] & 0xff) << 8) | (buffer[3] & 0xff));
		if (windowSizeIncrement == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invaid windowSizeIncrement:" + windowSizeIncrement);
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_WINDOW_UPDATE;
		}
		// if (windowSizeIncrement == 0) {
		// throw streamError(streamId, PROTOCOL_ERROR,
		// "Received WINDOW_UPDATE with delta 0 for stream: %d", streamId);
		// }
		if (this.streamId == 0) {
			assert LOG.debug(this.channelId + " call windowUpdate(" + windowSizeIncrement + "::int)");
			windowUpdate(windowSizeIncrement);
		} else {
			assert LOG.debug(this.channelId + " call streamWindowUpdate(" + windowSizeIncrement + "::int)");
			streamWindowUpdate(windowSizeIncrement);
		}
	}

	protected void handleDataFrame() {
		// assert this.cachePayload.ridx == 0;
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);

		int padding = 0;
		if (hasPadding) {
			padding = this.cachePayload.buffer[this.cachePayload.ridx++] & 0xFF;
			if (payloadLength < padding) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + " invalid padding:" + padding + " payloadLength:" + payloadLength);
				}
				this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
				return;
			}
			cachePayload.widx -= padding;
		}
		// this.recvWindowSize -= payloadLength;
		// assert LOG.debug(this.channelId+" call writeWindowUpdate(0:int,"+
		// (this.localConnectionInitialWinodwSize - recvWindowSize)+"::int)");

		// this.writeWindowUpdate(0, (this.localConnectionInitialWinodwSize -
		// recvWindowSize));
		if (payloadLength > 0) {
			assert LOG.debug(this.channelId + " call writeWindowUpdate(0::int," + payloadLength + "::int)");
			this.writeWindowUpdate(0, payloadLength);
		}
		assert LOG.debug(this.channelId + " call handleStreamData(" + payloadLength + "::int," + Http2FlagsUtil.endOfStream(frameFlag) + "::boolean)");
		this.handleStreamData(payloadLength, Http2FlagsUtil.endOfStream(frameFlag));
	}

	protected void handleContinuationFrame() {

		if (Http2FlagsUtil.endOfHeaders(frameFlag)) {

			DefaultHttpHeaders headers = this.decodeHeaders();
			assert LOG.debug(this.channelId + " decodeHeader:" + (headers != null ? headers.toString() : "--------INVALID HEADERS"));
			if (headers != null) {
				if (priorityInHeaders) {
					recvHeaders(headers, streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
				} else {
					recvHeaders(headers, endOfStreamInHeaders);
				}
			}
			this.streamIdOfHeaders = INVALID_STREAM_ID;
		}
	}

	private boolean priorityInHeaders = false;
	private int streamDependency = 0;
	private short weightInHeaders = 0;
	private boolean exclusiveInHeaders = false;
	private boolean endOfStreamInHeaders = false;

	protected void handleHeadersFrame() {
		assert this.cachePayload.ridx == 0;
		this.streamIdOfHeaders = this.streamId;
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		boolean endOfHeaders = Http2FlagsUtil.endOfHeaders(frameFlag);
		this.endOfStreamInHeaders = Http2FlagsUtil.endOfStream(frameType);
		byte[] buffer = this.cachePayload.buffer;
		int padding = 0;
		if (hasPadding) {
			padding = buffer[this.cachePayload.ridx] & 0xFF;
			cachePayload.widx -= padding;
		}
		if (payloadLength < padding) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid padding:" + padding + " payloadLength:" + payloadLength);
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}
		if (Http2FlagsUtil.priorityPresent(frameFlag)) {
			priorityInHeaders = true;
			long word1 = (((buffer[this.cachePayload.ridx++] & 0xff) << 24) | ((buffer[this.cachePayload.ridx++] & 0xff) << 16)
					| ((buffer[this.cachePayload.ridx++] & 0xff) << 8) | (buffer[this.cachePayload.ridx++] & 0xff)) & 0xFFFFFFFFL;
			exclusiveInHeaders = (word1 & 0x80000000L) != 0;
			streamDependency = (int) (word1 & 0x7FFFFFFFL);
			if (streamDependency == streamId) {
				// TODO impl
				// throw streamError(streamId, PROTOCOL_ERROR, "A stream cannot
				// depend on itself.");
			}
			weightInHeaders = (short) ((buffer[this.cachePayload.ridx++] & 0xFF) + 1);
			if (endOfHeaders) {
				DefaultHttpHeaders headers = this.decodeHeaders();
				assert LOG.debug(this.channelId + " decodeHeader:" + (headers != null ? headers.toString() : "--------INVALID HEADERS"));
				if (headers != null) {
					recvHeaders(headers, streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		} else {
			priorityInHeaders = false;
			if (endOfHeaders) {
				DefaultHttpHeaders headers = this.decodeHeaders();
				assert LOG.debug(this.channelId + " decodeHeader:" + (headers != null ? headers.toString() : "--------INVALID HEADERS"));
				if (headers != null) {
					recvHeaders(headers, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		}
	}

	private boolean doReadFrameHeader() {
		assert LOG.debug(this.rbuffer, this.ridx, this.widx);
		int nr = this.widx - this.ridx;
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		System.arraycopy(this.rBytes, ridx, this.frameReadBuffer, this.frameHeaderIndex, readSize);
		this.ridx += readSize;
		this.frameHeaderIndex += readSize;
		if (this.frameHeaderIndex == FRAME_CONFIG_HEADER_SIZE) {
			this.frameHeaderIndex = 0;

			this.payloadLength = (this.frameReadBuffer[0] & 0xff) << 16 | ((frameReadBuffer[1] & 0xff) << 8) | (frameReadBuffer[2] & 0xff);
			if (payloadLength > this.localMaxFrameSize) {
				if (LOG.enableWarn()) {
					LOG.warn(this.channelId + "error with http2 protocol, ==> read frame payload length is " + payloadLength + ", local maxFrameSize is "
							+ this.localMaxFrameSize);
				}
				this.currentState = Http2ProtocolError.ERROR_MAX_FRAME_SIZE;
				return true;
			}
			frameType = this.frameReadBuffer[3];
			this.frameFlag = this.frameReadBuffer[4];
			streamId = ((frameReadBuffer[5] & 0x7f) << 24 | (frameReadBuffer[6] & 0xff) << 16 | (frameReadBuffer[7] & 0xff) << 8 | frameReadBuffer[8] & 0xff);
			assert LOG.debug(this.channelId + frameHeaderInfo(" read frame header success", payloadLength, frameType, frameFlag, streamId));
			this.currentState = FRAME_STATE_READ_DATA;

			if (frameType == FRAME_TYPE_DATA) {
				this.cachePayload.reset(payloadLength);
				verifyDataFrame();
			} else if (frameType == FRAME_TYPE_HEADERS) {
				this.cachePayload.reset(payloadLength);
				verifyHeadersFrame();
			} else if (frameType == FRAME_TYPE_PRIORITY) {
				this.cachePayload.reset(payloadLength);
				verifyPriorityFrame();
			} else if (frameType == FRAME_TYPE_RST_STREAM) {
				this.cachePayload.reset(payloadLength);
				verifyRstStreamFrame();
			} else if (frameType == FRAME_TYPE_SETTINGS) {
				this.cachePayload.reset(payloadLength);
				verifySettingsFrame();
			} else if (frameType == FRAME_TYPE_PUSH_PROMISE) {
				this.cachePayload.reset(payloadLength);
				verifyPushPromiseFrame();
			} else if (frameType == FRAME_TYPE_PING) {
				this.cachePayload.reset(payloadLength);
				verifyPingFrame();
			} else if (frameType == FRAME_TYPE_GO_AWAY) {
				this.cachePayload.reset(payloadLength);
				verifyGoAwayFrame();
			} else if (frameType == FRAME_TYPE_WINDOW_UPDATE) {
				this.cachePayload.reset(payloadLength);
				verifyWindowUpdateFrame();
			} else if (frameType == FRAME_TYPE_CONTINUATION) {
				verifyContinuationFrame();
				this.cachePayload.ensureCapacity(this.cachePayload.widx + payloadLength);
			} else {
				verifyUnknownFrame();
			}
			return true;
		}
		this.clearReadBuffer();
		return false;
	}

	private void verifyUnknownFrame() {
		if (LOG.enableWarn()) {
			LOG.warn(this.channelId + " invalid frame type:" + frameType);
		}
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifyContinuationFrame() {
		if (this.streamId == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: STREAM_ID == 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders == INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is not a (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION_NOT;
			return;
		}
		if (streamId != this.streamIdOfHeaders) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: STREAM_ID not equals STREAM_ID with prev frame");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_STREAM_ID_WITH_CONTINUATION;
			return;
		}
		if (payloadLength < 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length < 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}
	}

	private void verifyWindowUpdateFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != 4) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 4");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE;
			return;
		}

	}

	private void verifyGoAwayFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength < 8) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 8");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_GOAWAY;
			return;
		}

	}

	private void verifyPingFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength != FRAME_PING_PAYLOAD_LENGTH) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 8");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PING;
			return;
		}

	}

	private void verifyPushPromiseFrame() {
		if (LOG.enableWarn()) {
			LOG.warn(this.channelId + " invalid frame: NOT SUPPORTED PROMISE");
		}
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifySettingsFrame() {
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// //IGNORE
		// }
		if (Http2FlagsUtil.ack(frameFlag) && payloadLength > 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING_ACK;
			return;
		}
		if (payloadLength % FRAME_SETTING_SETTING_ENTRY_LENGTH > 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length % 6 != 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING;
			return;
		}

	}

	private void verifyRstStreamFrame() {
		if (this.streamId == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: STREAM_ID == 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_RSTSTREAM_PAYLOAD_LENGTH) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 4");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_RSTSTREAM;
			return;
		}
	}

	private void verifyPriorityFrame() {
		if (this.streamId == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: STREAM_ID == 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_PRIORITY_PAYLOAD_LENGTH) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: payload length != 5");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PRIORITY;
			return;
		}
	}

	private void verifyHeadersFrame() {
		if (this.streamId == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: $STRING_ID == 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < (Http2FlagsUtil.getNumPriorityBytes(this.frameFlag)) + Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: invalid $PAYLOAD_LEN");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	private void verifyDataFrame() {
		if (this.streamId == 0) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: $STRING_ID == 0");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.streamIdOfHeaders != INVALID_STREAM_ID) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: prev frame is  a uncompleted frame with (Header OR Continuation )");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			if (LOG.enableWarn()) {
				LOG.warn(this.channelId + " invalid frame: invalid $PAYLOAD_LEN");
			}
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	protected DNode keepAliveNode;
	protected long keepAliveTimeout = Long.MAX_VALUE;

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue(this.executor.getKeepAliveQueue());
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

	private DefaultHttpHeaders decodeHeaders() {

		int index = 0;
		int nameLen = 0;
		int valueLen = 0;
		byte state = READ_HEADER_REPRESENTATION;
		boolean huffmanEncoded = false;
		long tmpLong = 0;
		HpackHeaderField header = null;
		String name = null;
		HpackUtil.IndexType indexType = HpackUtil.IndexType.NONE;
		DefaultHttpHeaders headers = new DefaultHttpHeaders();
		byte[] buffer = this.cachePayload.buffer;

		while (this.cachePayload.widx > this.cachePayload.ridx) {
			// ! this.headersPayload.isEmpty())
			switch (state) {
				case READ_HEADER_REPRESENTATION:
					byte b = buffer[this.cachePayload.ridx++];
					if (b < 0) {
						/* b = (-128~ -1) */
						index = b & 0x7F;
						/* index =(0~127) */
						switch (index) {
							case 0:/* index=0 b = -128 */
								this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
								return null;
							case 0x7F:
								/* index = 127 b = -1 */
								state = READ_INDEXED_HEADER;
								break;
							default:
								/* index=(1~126) b =(-127~ -2) */
								header = indexHeader(index);
								if (header == null) {
									this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
									return null;
								}
								headers.add(header.name, header.value);
						}
					} else if ((b & 0x40) == 0x40) {
						/* b = (64~127) */
						// Literal Header Field with Incremental Indexing
						indexType = HpackUtil.IndexType.INCREMENTAL;
						index = b & 0x3F;
						switch (index) {
							case 0:
								/* b = 64 index = 0 */
								state = READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
								break;
							case 0x3F:
								/* b =127 index = 63 */
								state = READ_INDEXED_HEADER_NAME;
								break;
							default:
								/* b = (65~126) index = (1~62) */
								header = indexHeader(index);
								if (header == null) {
									this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
									return null;
								}
								name = header.name;
								state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
						}
					} else if ((b & 0x20) == 0x20) {
						/* b = 32 ~ 63 */
						// Dynamic Table Size Update
						index = b & 0x1F;
						if (index == 0x1F) {/* b = 63 index == 31 */
							state = READ_MAX_DYNAMIC_TABLE_SIZE;
						} else {
							/* b = 32~ 62 index = 0~ 30 */
							this.remoteDynaTable.setCapacity(index);
							state = READ_HEADER_REPRESENTATION;
						}
					} else {
						/* b = 0 ~31 */
						// Literal Header Field without Indexing / never
						// Indexed
						indexType = ((b & 0x10) == 0x10) ? HpackUtil.IndexType.NEVER : HpackUtil.IndexType.NONE;
						index = b & 0x0F;

						switch (index) {
							case 0:
								/* index = 0 , b =(0 , 16) */
								state = READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
								break;
							case 0x0F:
								/* index = 15, b=(15,31) */
								state = READ_INDEXED_HEADER_NAME;
								break;
							default:
								// Index was stored as the prefix
								/* index = (1~14) , b =(1~14 ,17~30) */
								header = indexHeader(index);
								if (header == null) {
									this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
									return null;
								}
								name = header.name;
								state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
						}
					}
					break;

				case READ_MAX_DYNAMIC_TABLE_SIZE:
					tmpLong = decodeULE128((long) index);
					if (tmpLong == Long.MAX_VALUE) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					this.remoteDynaTable.setCapacity(tmpLong);
					state = READ_HEADER_REPRESENTATION;
					break;
				case READ_INDEXED_HEADER:
					tmpLong = decodeULE128((long) index);
					if (tmpLong > Integer.MAX_VALUE) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					header = indexHeader((int) tmpLong);
					if (header == null) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					headers.add(header.name, header.value);
					state = READ_HEADER_REPRESENTATION;
					break;

				case READ_INDEXED_HEADER_NAME:
					// Header Name matches an entry in the Header Table
					tmpLong = decodeULE128((long) index);
					if (tmpLong > Integer.MAX_VALUE) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					header = indexHeader((int) tmpLong);
					if (header == null) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					name = header.name;
					state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
					break;

				case READ_LITERAL_HEADER_NAME_LENGTH_PREFIX:
					b = buffer[this.cachePayload.ridx++]; // readByteInQueue(this.headersPayload);
					huffmanEncoded = (b & 0x80) == 0x80;
					index = b & 0x7F;
					if (index == 0x7f) {
						state = READ_LITERAL_HEADER_NAME_LENGTH;
					} else {
						nameLen = index;
						state = READ_LITERAL_HEADER_NAME;
					}
					break;
				case READ_LITERAL_HEADER_NAME_LENGTH:
					tmpLong = decodeULE128((long) index);
					if (tmpLong > Integer.MAX_VALUE) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					nameLen = (int) tmpLong;
					state = READ_LITERAL_HEADER_NAME;
					break;
				case READ_LITERAL_HEADER_NAME:
					// Wait until entire name is readable
					if (this.cachePayload.widx - this.cachePayload.ridx < nameLen) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}

					name = readStringLiteral(nameLen, huffmanEncoded);
					if (null == name) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					this.cachePayload.ridx += nameLen;
					state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
					break;

				case READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX:
					b = buffer[this.cachePayload.ridx++];
					huffmanEncoded = (b & 0x80) == 0x80;
					index = b & 0x7F;
					switch (index) {
						case 0x7f:
							state = READ_LITERAL_HEADER_VALUE_LENGTH;
							break;
						case 0:
							headers.add(name, StringUtil.EMPTY_STRING);
							if (indexType == HpackUtil.IndexType.INCREMENTAL) {
								remoteDynaTable.add(new HpackHeaderField(name, StringUtil.EMPTY_STRING));
							}
							state = READ_HEADER_REPRESENTATION;
							break;
						default:
							valueLen = index;
							state = READ_LITERAL_HEADER_VALUE;
					}
					break;

				case READ_LITERAL_HEADER_VALUE_LENGTH:
					// Header Value is a Literal String
					tmpLong = decodeULE128(index);
					if (tmpLong > Integer.MAX_VALUE) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					valueLen = (int) tmpLong;
					state = READ_LITERAL_HEADER_VALUE;
					break;

				case READ_LITERAL_HEADER_VALUE:
					// Wait until entire value is readable
					if (this.cachePayload.widx - this.cachePayload.ridx < valueLen) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					// this.cachePayload.ridx += valueLen;
					String value = readStringLiteral(valueLen, huffmanEncoded);
					if (value == null) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
					this.cachePayload.ridx += valueLen;
					headers.add(name, value);
					if (indexType == HpackUtil.IndexType.INCREMENTAL) {
						remoteDynaTable.add(new HpackHeaderField(name, value));
					}
					state = READ_HEADER_REPRESENTATION;
					break;

				default:
					throw new Error("should not reach here state: " + state);
			}
		}

		return headers;

	}

	private String readStringLiteral(int length, boolean huffmanEncoded) {
		if (length == 0)
			return StringUtil.EMPTY_STRING;
		if (huffmanEncoded) {
			assert LOG.debug(this.cachePayload.buffer, this.cachePayload.ridx, length);
			return Huffman.decode(this.cachePayload.buffer, this.cachePayload.ridx, length);
		}
		String ret = new String(this.cachePayload.buffer, this.cachePayload.ridx, length, StringUtil.US_ASCII);
		return ret;
	}

	public long decodeULE128(long result) {
		assert result <= 0x7f && result >= 0;
		int shift = 0;
		final boolean resultStartedAtZero = result == 0;
		while (this.cachePayload.widx > this.cachePayload.ridx) {
			byte b = this.cachePayload.buffer[this.cachePayload.ridx++];
			if (shift == 56 && ((b & 0x80) != 0 || b == 0x7F && !resultStartedAtZero)) {
				return Long.MAX_VALUE;
			}
			if ((b & 0x80) == 0) {
				return result + ((b & 0x7FL) << shift);
			}
			result += (b & 0x7FL) << shift;
			shift += 7;
		}
		return Long.MAX_VALUE;
	}

	private HpackHeaderField indexHeader(int index) {
		if (index <= HpackStaticTable.length) {
			return HpackStaticTable.getEntry(index);
		}
		if (index - HpackStaticTable.length <= this.remoteDynaTable.length()) {
			return remoteDynaTable.getEntry(index - HpackStaticTable.length);
		}
		return null;
	}
	/* =========================== log util================ */

	public static String frameHeaderInfo(String prefix, byte[] buffer, int idx) {
		int len = (buffer[idx++] & 0xff) << 16 | ((buffer[idx++] & 0xff) << 8) | (buffer[idx++] & 0xff);
		byte type = buffer[idx++];
		byte flag = buffer[idx++];
		int id = ((buffer[idx++] & 0x7f) << 24 | (buffer[idx++] & 0xff) << 16 | (buffer[idx++] & 0xff) << 8 | buffer[idx++] & 0xff);
		return frameHeaderInfo(prefix, len, type, flag, id);
	}

	public static String frameHeaderInfo(String prefix, ByteBuffer buffer) {
		buffer.mark();
		int len = (buffer.get() & 0xff) << 16 | ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
		byte type = buffer.get();
		byte flag = buffer.get();
		int id = ((buffer.get() & 0x7f) << 24 | (buffer.get() & 0xff) << 16 | (buffer.get() & 0xff) << 8 | buffer.get() & 0xff);
		buffer.reset();
		return frameHeaderInfo(prefix, len, type, flag, id);
	}

	public static String frameHeaderInfo(String prefix, int len, byte type, byte flag, int sid) {
		StringBuilder sb = new StringBuilder();
		sb.append(prefix).append(":\r\n{").append("type:");
		if (type == FRAME_TYPE_DATA) {
			sb.append("FRAME_TYPE_DATA,\r\nendStream:").append(Http2FlagsUtil.endOfStream(flag)).append(",\r\npadded:")
					.append(Http2FlagsUtil.paddingPresent(flag));
		} else if (type == FRAME_TYPE_HEADERS) {
			sb.append("FRAME_TYPE_HEADERS,\r\nendStream:").append(Http2FlagsUtil.endOfStream(flag)).append(",\r\npadded:")
					.append(Http2FlagsUtil.paddingPresent(flag)).append(",\r\npriority:").append(Http2FlagsUtil.priorityPresent(flag))
					.append(",\r\nendHeaders:").append(Http2FlagsUtil.endOfHeaders(flag));
		} else if (type == FRAME_TYPE_PRIORITY) {
			sb.append("FRAME_TYPE_PRIORITY");
		} else if (type == FRAME_TYPE_RST_STREAM) {
			sb.append("FRAME_TYPE_RST_STREAM");
		} else if (type == FRAME_TYPE_SETTINGS) {
			sb.append("FRAME_TYPE_SETTINGS,\r\nack:").append(Http2FlagsUtil.ack(flag));
		} else if (type == FRAME_TYPE_PUSH_PROMISE) {
			sb.append("FRAME_TYPE_PUSH_PROMISE,\r\nendStream:").append(Http2FlagsUtil.endOfStream(flag)).append(",\r\npadded:")
					.append(Http2FlagsUtil.paddingPresent(flag)).append(",\r\nendHeaders:").append(Http2FlagsUtil.endOfHeaders(flag));
		} else if (type == FRAME_TYPE_PING) {
			sb.append("FRAME_TYPE_PING,\r\nack:").append(Http2FlagsUtil.ack(flag));
		} else if (type == FRAME_TYPE_GO_AWAY) {
			sb.append("FRAME_TYPE_GO_AWAY");
		} else if (type == FRAME_TYPE_WINDOW_UPDATE) {
			sb.append("FRAME_TYPE_WINDOW_UPDATE");
		} else if (type == FRAME_TYPE_CONTINUATION) {
			sb.append("FRAME_TYPE_CONTINUATION,\r\nendHeaders:").append(Http2FlagsUtil.endOfHeaders(flag));
		} else
			sb.append("FRAME_TYPE_UNKNOWN,\r\nflag:").append(flag);
		sb.append(",\r\npayloadLength:").append(len).append(",\r\nstreamId:").append(sid).append("}");
		return sb.toString();
	}

	public static String frameTypeString(byte b) {
		if (b == FRAME_TYPE_DATA) {
			return "FRAME_TYPE_DATA";
		} else if (b == FRAME_TYPE_HEADERS) {
			return "FRAME_TYPE_HEADERS";
		} else if (b == FRAME_TYPE_PRIORITY) {
			return "FRAME_TYPE_PRIORITY";
		} else if (b == FRAME_TYPE_RST_STREAM) {
			return "FRAME_TYPE_RST_STREAM";
		} else if (b == FRAME_TYPE_SETTINGS) {
			return "FRAME_TYPE_SETTINGS";
		} else if (b == FRAME_TYPE_PUSH_PROMISE) {
			return "FRAME_TYPE_PUSH_PROMISE";
		} else if (b == FRAME_TYPE_PING) {
			return "FRAME_TYPE_PING";
		} else if (b == FRAME_TYPE_GO_AWAY) {
			return "FRAME_TYPE_GO_AWAY";
		} else if (b == FRAME_TYPE_WINDOW_UPDATE) {
			return "FRAME_TYPE_WINDOW_UPDATE";
		} else if (b == FRAME_TYPE_CONTINUATION) {
			return "FRAME_TYPE_CONTINUATION";
		} else
			return "FRAME_TYPE_UNKNOWN";
	}

	public static void main(String[] args){
		byte[] buffer = new byte[]{0x46,0x72,0x61,0x6d,0x65,0x72,0x20,0x65,0x72,0x72,0x6f,0x72,0x3a,0x20,0x32,0x20,
				0x28,0x49,0x4e,0x56,0x41,0x4c,0x49,0x44,0x5f,0x43,0x4f,0x4e,0x54,0x52,0x4f,0x4c,
				0x5f,0x46,0x52,0x41,0x4d,0x45,0x29,0x2e};
		System.out.println(new String(buffer,StringUtil.US_ASCII));
		
	}
/*
0x50,0x52,0x49,0x20,0x2a,0x20,0x48,0x54,0x54,0x50,0x2f,0x32,0x2e,0x30,0xd,0xa,
0xd,0xa,0x53,0x4d,0xd,0xa,0xd,0xa, ---prefaced

0x0,0x0,0x12,0x4,0x0,0x0,0x0,0x0,0x0,  --  SETTING FRAME_FLAG:value = 0  FRAME_PAYLOAD_LENGTH:18   FRAME_STREAM_ID:0
0x0,0x1,0x0,0x1,0x0,0x0,0x0,0x3,0x0,0x0,0x3,0xe8,0x0,0x4,0x0,
0x60,0x0,0x0,

0x0,0x0,0x4,0x8,0x0,0x0,0x0,0x0,0x0, ---WINDOWUPDATE  FRAME_FLAG:value = 0 FRAME_PAYLOAD_LENGTH:4 FRAME_STREAM_ID:0 
0x0,0xef,0x0,0x1,

0x0,0x0,0xec,0x1,0x25,0x0,0x0,0x0,0x1, ---HEADER  FRAME_FLAG:value = 37 (ACK,END_OF_HEADERS,END_OF_STREAM,PRIORITY_PRESENT,) FRAME_PAYLOAD_LENGTH:236 FRAME_STREAM_ID:1
0x80,0x0,0x0,0x0,0xff,0x82,0x41,
0x8a,0xb,0xe2,0x5c,0x2e,0x3c,0xbb,0x2b,0x85,0xc7,0x3f,0x87,0x84,0x58,0x87,0xa4,
0x7e,0x56,0x1c,0xc5,0x80,0x1f,0x40,0x92,0xb6,0xb9,0xac,0x1c,0x85,0x58,0xd5,0x20,
0xa4,0xb6,0xc2,0xad,0x61,0x7b,0x5a,0x54,0x25,0x1f,0x1,0x31,0x7a,0xd8,0xd0,0x7f,
0x66,0xa2,0x81,0xb0,0xda,0xe0,0x53,0xfa,0xe4,0x6a,0xa4,0x3f,0x84,0x29,0xa7,0x7a,
0x8e,0x2e,0x1f,0xb5,0x39,0x1a,0xa7,0x1a,0xfb,0x53,0xcb,0x8d,0x7f,0x6a,0x43,0x5d,
0x74,0x17,0x91,0x63,0xcc,0x64,0xb0,0xdb,0x2e,0xae,0xcb,0x8a,0x7f,0x59,0xb1,0xef,
0xd1,0x9f,0xe9,0x4a,0xd,0xd4,0xaa,0x62,0x29,0x3a,0x9f,0xfb,0x52,0xf4,0xf6,0x1e,
0x92,0xb0,0xe0,0x57,0x2,0xec,0x85,0xc6,0x57,0x8,0x0,0xa6,0xe1,0xca,0x3b,0xc,
0xc3,0x6c,0xba,0xbb,0x2e,0x7f,0x53,0xc0,0x49,0x7c,0xa5,0x89,0xd3,0x4d,0x1f,0x43,
0xae,0xba,0xc,0x41,0xa4,0xc7,0xa9,0x8f,0x33,0xa6,0x9a,0x3f,0xdf,0x9a,0x68,0xfa,
0x1d,0x75,0xd0,0x62,0xd,0x26,0x3d,0x4c,0x79,0xa6,0x8f,0xbe,0xd0,0x1,0x77,0xfe,
0x8d,0x48,0xe6,0x2b,0x1e,0xb,0x1d,0x7f,0x46,0xa4,0x73,0x15,0x81,0xd7,0x54,0xdf,
0x5f,0x2c,0x7c,0xfd,0xf6,0x80,0xb,0xbd,0x50,0x8d,0x9b,0xd9,0xab,0xfa,0x52,0x42,
0xcb,0x40,0xd2,0x5f,0xa5,0x23,0xb3,0x51,0x8c,0xf7,0x3a,0xd7,0xb4,0xfd,0x7b,0x9f,
0xef,0xb4,0x0,0x5d,0xef


~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x0,0x0,0xa,0x1,0x24,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0xf,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x3f,0xe1,0xff,0x3,0x88,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x0,0x0,0x18,0x0,0x1,0x0,0x0,0x0,0x1,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x48,0x65,0x6c,0x6c,0x6f,0x20,0x57,0x6f,0x72,0x6c,0x64,0x20,0x2d,0x20,0x76,0x69,
0x61,0x20,0x48,0x54,0x54,0x50,0x2f,0x32,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
????????????????????????????
0x0,0x0,0x0,0x4,0x1,0x0,0x0,0x0,0x0,????????????????????????????
FRAME_TYPE:4
FRAME_FLAG:value = 1 (ACK,END_OF_STREAM,)
FRAME_PAYLOAD_LENGTH:0
FRAME_STREAM_ID:0
????????????????????????????
0x0,0x0,0x4d,0x1,0x25,0x0,0x0,0x0,0x3,0x80,0x0,0x0,0x0,0xdb,0x82,0xc4,
0x87,0x0,0x84,0xb9,0x58,0xd3,0x3f,0x89,0x62,0x51,0xf7,0x31,0xf,0x52,0xe6,0x21,
0xff,0xc1,0x53,0x9e,0x35,0x23,0x98,0xac,0x78,0x2c,0x75,0xfd,0x1a,0x91,0xcc,0x56,
0x7,0x5d,0x53,0x7d,0x1a,0x91,0xcc,0x56,0x3e,0x7e,0xbe,0x58,0xf9,0xfb,0xed,0x0,
0x17,0x7b,0x73,0x90,0x9d,0x29,0xad,0x17,0x18,0x60,0x2f,0x89,0x70,0xb8,0xf2,0xec,
0xae,0x17,0x1c,0x63,0xc1,0xc0,????????????????????????????
FRAME_TYPE:1
FRAME_FLAG:value = 37 (ACK,END_OF_HEADERS,END_OF_STREAM,PRIORITY_PRESENT,)
FRAME_PAYLOAD_LENGTH:77
FRAME_STREAM_ID:3
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x0,0x0,0x6,0x1,0x24,0x0,0x0,0x0,0x3,0x0,0x0,0x0,0x0,0xf,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x88,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x0,0x0,0x18,0x0,0x1,0x0,0x0,0x0,0x3,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0x48,0x65,0x6c,0x6c,0x6f,0x20,0x57,0x6f,0x72,0x6c,0x64,0x20,0x2d,0x20,0x76,0x69,
0x61,0x20,0x48,0x54,0x54,0x50,0x2f,0x32,
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	 
	 
	 * */

}
