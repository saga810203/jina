package org.jfw.jina.http2.impl;

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
import org.jfw.jina.http2.headers.HpackDynamicTable;
import org.jfw.jina.http2.headers.HpackHeaderField;
import org.jfw.jina.http2.headers.HpackStaticTable;
import org.jfw.jina.http2.headers.HpackUtil;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.StringUtil;

public abstract class Http2FrameReader<T extends Http2AsyncExecutor> extends AbstractNioAsyncChannel<T>
		implements Http2Connection, FrameWriter, KeepAliveCheck {
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

	private static final Node ROOT = buildTree(HpackUtil.HUFFMAN_CODES, HpackUtil.HUFFMAN_CODE_LENGTHS);

	HpackHuffmanDecoder huffmanDecoder = new HpackHuffmanDecoder();

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

	int recvWindowSize = 65535;

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
	}

	protected abstract void handleInputClose();

	protected abstract void handleProtocolError();

	protected void handleRead(int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
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
			if(this.activeStreams ==0){
				this.addKeepAliveCheck();
			}
		} else {
			handleInputClose();
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
				// this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
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
		goAway(lastStreamId, errorCode);
	}

	private void handleRstStreamFrame() {
		byte[] buffer = cachePayload.buffer;
		long errorCode = ((buffer[0] & 0xff) << 24 | (buffer[1] & 0xff) << 16 | (buffer[2] & 0xff) << 8 | buffer[3] & 0xff) & 0xFFFFFFFFL;
		resetStream(errorCode);
	}

	private void handleUnknownFrame() {

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
					this.applySetting(settings);
				} catch (IllegalArgumentException e) {
					this.currentState = Http2ProtocolError.ERROR_INVALID_SETTING_VALUE;

					return;
				}
			}
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
		handlePriority(streamDependency, weight, exclusive);
	}

	protected void handleWindowUpdateFrame() {
		byte[] buffer = this.cachePayload.buffer;
		int windowSizeIncrement = (((buffer[0] & 0x7f) << 24) | ((buffer[1] & 0xff) << 16) | ((buffer[2] & 0xff) << 8) | (buffer[3] & 0xff));
		if (windowSizeIncrement == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_WINDOW_UPDATE;
		}
		// if (windowSizeIncrement == 0) {
		// throw streamError(streamId, PROTOCOL_ERROR,
		// "Received WINDOW_UPDATE with delta 0 for stream: %d", streamId);
		// }
		if (this.streamId == 0) {
			windowUpdate(windowSizeIncrement);
		} else {
			streamWindowUpdate(windowSizeIncrement);
		}
	}

	protected void handleDataFrame() {
		assert this.cachePayload.ridx == 0;
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);

		int padding = 0;
		if (hasPadding) {
			padding = this.cachePayload.buffer[this.cachePayload.ridx++] & 0xFF;
			if (payloadLength < padding) {
				this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
				return;
			}
			cachePayload.widx -= padding;
		}
		this.recvWindowSize -= payloadLength;
		if (this.recvWindowSize < 8192) {
			this.writeWindowUpdate(0, (this.localConnectionInitialWinodwSize - recvWindowSize));
		}
		this.handleStreamData(payloadLength, Http2FlagsUtil.endOfStream(frameFlag));
	}

	protected void handleContinuationFrame() {
		if (Http2FlagsUtil.endOfHeaders(frameFlag)) {
			DefaultHttpHeaders headers = this.decodeHeaders();
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
				if (headers != null) {
					recvHeaders(headers, streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		} else {
			priorityInHeaders = false;
			if (endOfHeaders) {
				DefaultHttpHeaders headers = this.decodeHeaders();
				if (headers != null) {
					recvHeaders(headers, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		}
	}

	private boolean doReadFrameHeader() {
		int nr = this.widx - this.ridx;
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		System.arraycopy(this.rBytes, ridx, this.frameReadBuffer, this.frameHeaderIndex, readSize);
		this.ridx += readSize;
		this.frameHeaderIndex += readSize;
		if (this.frameHeaderIndex == FRAME_CONFIG_HEADER_SIZE) {
			this.frameHeaderIndex = 0;

			this.payloadLength = (this.frameReadBuffer[0] & 0xff) << 16 | ((frameReadBuffer[1] & 0xff) << 8) | (frameReadBuffer[2] & 0xff);
			if (payloadLength > this.localMaxFrameSize) {
				this.currentState = Http2ProtocolError.ERROR_MAX_FRAME_SIZE;
				return true;
			}
			frameType = this.frameReadBuffer[3];
			this.frameFlag = this.frameReadBuffer[4];
			streamId = ((frameReadBuffer[5] & 0x7f) << 24 | (frameReadBuffer[6] & 0xff) << 16 | (frameReadBuffer[7] & 0xff) << 8 | frameReadBuffer[8] & 0xff);

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


	protected DNode keepAliveNode;
	protected long keepAliveTimeout = Long.MAX_VALUE;

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
					this.cachePayload.ridx += nameLen;
					name = readStringLiteral(nameLen, huffmanEncoded);
					if (null == name) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}

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
					this.cachePayload.ridx += valueLen;
					String value = readStringLiteral(valueLen, huffmanEncoded);
					if (value == null) {
						this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
						return null;
					}
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
			return huffmanDecoder.decode(length);
		}
		String ret = new String(this.cachePayload.buffer, this.cachePayload.ridx, length, StringUtil.US_ASCII);
		this.cachePayload.ridx += length;
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

	final class HpackHuffmanDecoder {
		private byte[] bytes;
		private int index;
		private Node node;
		private int current;
		private int currentBits;
		private int symbolBits;

		public String decode(int length) {
			reset(length);
			if (process(length))

				return end();
			return null;
		}

		void reset(int length) {
			node = ROOT;
			current = 0;
			currentBits = 0;
			symbolBits = 0;
			bytes = new byte[32];
			index = 0;
		}

		public boolean process(int length) {
			byte[] buffer = cachePayload.buffer;
			while (length > 0) {
				byte value = buffer[cachePayload.ridx++];
				current = (current << 8) | (value & 0xFF);
				currentBits += 8;
				symbolBits += 8;
				do {
					node = node.children[(current >>> (currentBits - 8)) & 0xFF];
					currentBits -= node.bits;
					if (node.isTerminal()) {
						if (node.symbol == HpackUtil.HUFFMAN_EOS) {
							node = ROOT;
							bytes = null;
							return false;
						}
						append(node.symbol);
						node = ROOT;
						symbolBits = currentBits;
					}
				} while (currentBits >= 8);
			}

			return true;
		}

		String end() {
			while (currentBits > 0) {
				node = node.children[(current << (8 - currentBits)) & 0xFF];
				if (node.isTerminal() && node.bits <= currentBits) {
					if (node.symbol == HpackUtil.HUFFMAN_EOS) {
						node = ROOT;
						bytes = null;
						throw null;
					}
					currentBits -= node.bits;
					append(node.symbol);
					node = ROOT;
					symbolBits = currentBits;
				} else {
					break;
				}
			}
			int mask = (1 << symbolBits) - 1;
			if (symbolBits > 7 || (current & mask) != mask) {
				throw null;
			}
			StringBuilder sb = new StringBuilder(index);
			for (int i = 0; i < index; ++i) {
				sb.append((char) bytes[i]);
			}
			bytes = null;
			return sb.toString();
		}

		private void append(int i) {
			if (bytes.length == index) {
				final int newLength = bytes.length >= 1024 ? bytes.length + 32 : bytes.length << 1;
				byte[] newBytes = new byte[newLength];
				System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
				bytes = newBytes;
			}
			bytes[index++] = (byte) i;
		}
	}

	private static Node buildTree(int[] codes, byte[] lengths) {
		Node root = new Node();
		for (int i = 0; i < codes.length; i++) {
			insert(root, i, codes[i], lengths[i]);
		}
		return root;
	}

	private static void insert(Node root, int symbol, int code, byte length) {
		// traverse tree using the most significant bytes of code
		Node current = root;
		while (length > 8) {
			if (current.isTerminal()) {
				throw new IllegalStateException("invalid Huffman code: prefix not unique");
			}
			length -= 8;
			int i = (code >>> length) & 0xFF;
			if (current.children[i] == null) {
				current.children[i] = new Node();
			}
			current = current.children[i];
		}

		Node terminal = new Node(symbol, length);
		int shift = 8 - length;
		int start = (code << shift) & 0xFF;
		int end = 1 << shift;
		for (int i = start; i < start + end; i++) {
			current.children[i] = terminal;
		}
	}

	static final class Node {

		private final int symbol; // terminal nodes have a symbol
		private final int bits; // number of bits matched by the node
		private final Node[] children; // internal nodes have children

		/**
		 * Construct an internal node
		 */
		Node() {
			symbol = 0;
			bits = 8;
			children = new Node[256];
		}

		/**
		 * Construct a terminal node
		 *
		 * @param symbol
		 *            the symbol the node represents
		 * @param bits
		 *            the number of bits matched by this node
		 */
		Node(int symbol, int bits) {
			assert bits > 0 && bits <= 8;
			this.symbol = symbol;
			this.bits = bits;
			children = null;
		}

		private boolean isTerminal() {
			return children == null;
		}
	}

}
