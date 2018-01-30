package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.NioAsyncChannel;
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
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.StringUtil;

public abstract class Http2FrameReader implements Http2Connection, FrameWriter, KeepAliveCheck, NioAsyncChannel {
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

	protected HpackHuffmanDecoder huffmanDecoder = new HpackHuffmanDecoder();

	// local Setting

	protected boolean localEnablePush = false;
	protected long localMaxConcurrentStreams = Long.MAX_VALUE;
	protected int localInitialWindowSize = 65535;
	protected int localMaxFrameSize = 16777215;

	// remote Setting
	protected long remoteHeaderTableSize = 4096;

	// this (MAX_HEADER_LIST_SIZE) unknow so don't check in recv , but send use
	// remote setting
	protected long maxHeaderListSize = Long.MAX_VALUE;

	protected int recvWindowSize = 65535;

	protected HpackDynamicTable remoteDynaTable = new HpackDynamicTable(4096);

	protected final Http2AsyncExecutor executor;
	protected final SocketChannel javaChannel;
	protected final SelectionKey key;

	protected final byte[] frameReadBuffer = new byte[64];
	protected int frameHeaderIndex = 0;

	protected int payloadLength = 0;
	protected byte frameType = 0;
	protected byte frameFlag = 0;
	protected int streamId = 0;

	protected Queue<InputBuf> framePayload;
	protected int payloadIndex = 0;

	protected Queue<InputBuf> headersPayload;
	protected Queue<InputBuf> dataPayload;
	// protected boolean headerProcessing = false;
	protected long streamIdOfHeaders = INVALID_STREAM_ID;

	protected byte currentState = FRAME_STATE_READ_HEADER;

	protected boolean goAwayed = false;

	protected Http2FrameReader(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key) {
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.key = key;
		this.framePayload = executor.newQueue();
		this.headersPayload = executor.newQueue();
		this.dataPayload = executor.newQueue();
		key.attach(this);
	}

	protected abstract void handleInputClose();

	@Override
	public abstract void keepAliveTimeout();

	protected abstract void handleProtocolError();

	protected boolean fixLenPayload = false;

	protected void handleRead(InputBuf buf, int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
			while (buf.readable()) {
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
				if (fixLenPayload) {
					buf.readBytes(this.frameReadBuffer, this.payloadIndex, readSize);
				} else {
					this.framePayload.offer(buf.duplicate(readSize));
					buf.skipBytes(readSize);
				}
			} else {
				if (fixLenPayload) {
					buf.readBytes(this.frameReadBuffer, this.payloadIndex, readSize);
				} else {
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
				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
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
		this.readCacheBytesInQueue(this.framePayload, 8);

		int lastStreamId = ((frameReadBuffer[0] & 0x7f) << 24 | (frameReadBuffer[1] & 0xff) << 16 | (frameReadBuffer[2] & 0xff) << 8
				| frameReadBuffer[3] & 0xff);
		long errorCode = ((frameReadBuffer[4] & 0xff) << 24 | (frameReadBuffer[5] & 0xff) << 16 | (frameReadBuffer[6] & 0xff) << 8 | frameReadBuffer[7] & 0xff)
				& 0xFFFFFFFFL;
		goAway(lastStreamId, errorCode);
		this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
	}

	private void handleRstStreamFrame() {
		long errorCode = ((frameReadBuffer[0] & 0xff) << 24 | (frameReadBuffer[1] & 0xff) << 16 | (frameReadBuffer[2] & 0xff) << 8 | frameReadBuffer[3] & 0xff)
				& 0xFFFFFFFFL;
		resetStream(errorCode);
	}

	private void handleUnknownFrame() {
		framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
	}

	protected void handlePingFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			recvPingAck(this.frameReadBuffer);
		} else {
			writePingAck(this.frameReadBuffer);
		}
	}

	protected void handleSettingsFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			recvSettingAck();
		} else {
			int numSettings = payloadLength / FRAME_SETTING_SETTING_ENTRY_LENGTH;
			Http2Settings settings = new Http2Settings();
			for (int index = 0; index < numSettings; ++index) {
				this.readCacheBytesInQueue(this.framePayload, FRAME_SETTING_SETTING_ENTRY_LENGTH);

				char id = (char) (((frameReadBuffer[0] << 8) | (frameReadBuffer[1] & 0xFF)) & 0xffff);
				long value = 0xffffffffL & (((frameReadBuffer[2] & 0xff) << 24) | ((frameReadBuffer[3] & 0xff) << 16) | ((frameReadBuffer[4] & 0xff) << 8)
						| (frameReadBuffer[5] & 0xff));
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
					// TODO
					this.currentState = Http2ProtocolError.ERROR_INVALID_SETTING_VALUE;
					this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
					return;
				}
			}
			applySetting(settings);
			writeSettingAck();
		}
	}

	protected void handlePriorityFrame() {
		long word1 = (((this.frameReadBuffer[0] & 0xff) << 24) | ((this.frameReadBuffer[2] & 0xff) << 16) | ((this.frameReadBuffer[3] & 0xff) << 8)
				| (this.frameReadBuffer[4] & 0xff)) & 0xFFFFFFFFL;

		final boolean exclusive = (word1 & 0x80000000L) != 0;
		final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
		if (streamDependency == streamId) {
			// TODO impl throw streamError(streamId, PROTOCOL_ERROR, "A stream
			// cannot depend on itself.");
		}
		final short weight = (short) ((this.frameReadBuffer[4] & 0xFF) + 1);
		handlePriority(streamDependency, weight, exclusive);
	}

	protected void handleWindowUpdateFrame() {
		this.readCacheBytesInQueue(this.framePayload, 4);
		int windowSizeIncrement = (((frameReadBuffer[0] & 0x7f) << 24) | ((frameReadBuffer[1] & 0xff) << 16) | ((frameReadBuffer[2] & 0xff) << 8)
				| (frameReadBuffer[3] & 0xff));
		assert this.framePayload.isEmpty();
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
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		int oldPayloadSize = payloadLength;
		int padding = 0;
		if (hasPadding) {
			padding = this.readUByteInQueue(this.framePayload);
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

		this.recvWindowSize -= oldPayloadSize;
		if (this.recvWindowSize < 8192) {
			this.writeWindowUpdate(0, (this.localInitialWindowSize - recvWindowSize));
		}
		this.handleStreamData(oldPayloadSize, Http2FlagsUtil.endOfStream(frameFlag));
		assert this.dataPayload.isEmpty();
	}

	protected void handleContinuationFrame() {
		if (this.payloadLength > 0) {
			this.cacheHeaderLength += payloadLength;
			this.framePayload.offerTo(this.headersPayload);
		}
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

	private int cacheHeaderLength = 0;

	protected void handleHeadersFrame() {
		this.streamIdOfHeaders = this.streamId;
		boolean hasPadding = Http2FlagsUtil.paddingPresent(frameFlag);
		boolean endOfHeaders = Http2FlagsUtil.endOfHeaders(frameFlag);
		this.endOfStreamInHeaders = Http2FlagsUtil.endOfStream(frameType);
		int padding = 0;
		if (hasPadding) {
			padding = this.readUByteInQueue(this.framePayload);
			--payloadLength;
		}

		if (payloadLength < padding) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			return;
		}

		if (Http2FlagsUtil.priorityPresent(frameFlag)) {
			priorityInHeaders = true;
			this.readCacheBytesInQueue(this.framePayload, 4);
			long word1 = (((this.frameReadBuffer[0] & 0xff) << 24) | ((this.frameReadBuffer[2] & 0xff) << 16) | ((this.frameReadBuffer[3] & 0xff) << 8)
					| (this.frameReadBuffer[4] & 0xff)) & 0xFFFFFFFFL;
			payloadLength -= 4;

			exclusiveInHeaders = (word1 & 0x80000000L) != 0;
			streamDependency = (int) (word1 & 0x7FFFFFFFL);
			if (streamDependency == streamId) {
				// TODO impl
				// throw streamError(streamId, PROTOCOL_ERROR, "A stream cannot
				// depend on itself.");
			}
			weightInHeaders = (short) (this.readUByteInQueue(this.framePayload) + 1);
			--payloadLength;

			if (padding != 0) {
				this.cacheHeaderLength = payloadLength - padding;
				if (cacheHeaderLength > 0)
					this.slicePayload(cacheHeaderLength, this.headersPayload);
				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			} else {
				this.cacheHeaderLength = payloadLength;
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
			}
			if (endOfHeaders) {
				DefaultHttpHeaders headers = this.decodeHeaders();
				if (headers != null) {
					recvHeaders(headers, streamDependency, weightInHeaders, exclusiveInHeaders, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		} else {
			priorityInHeaders = false;
			if (padding != 0) {
				cacheHeaderLength = payloadLength - padding;
				if (cacheHeaderLength > 0)
					this.slicePayload(cacheHeaderLength, this.headersPayload);

				this.framePayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
			} else {
				cacheHeaderLength = payloadLength;
				if (payloadLength > 0) {
					this.framePayload.offerTo(this.headersPayload);
				}
			}
			if (endOfHeaders) {
				DefaultHttpHeaders headers = this.decodeHeaders();
				if (headers != null) {
					recvHeaders(headers, endOfStreamInHeaders);
				}
				this.streamIdOfHeaders = INVALID_STREAM_ID;
			}
		}
	}

	private boolean doReadFrameHeader(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		buf.readBytes(this.frameReadBuffer, this.frameHeaderIndex, readSize);
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
				fixLenPayload = false;
				verifyDataFrame();
			} else if (frameType == FRAME_TYPE_HEADERS) {
				fixLenPayload = false;
				verifyHeadersFrame();
			} else if (frameType == FRAME_TYPE_PRIORITY) {
				fixLenPayload = true;
				verifyPriorityFrame();
			} else if (frameType == FRAME_TYPE_RST_STREAM) {
				fixLenPayload = true;
				verifyRstStreamFrame();
			} else if (frameType == FRAME_TYPE_SETTINGS) {
				fixLenPayload = false;
				verifySettingsFrame();
			} else if (frameType == FRAME_TYPE_PUSH_PROMISE) {
				fixLenPayload = false;
				verifyPushPromiseFrame();
			} else if (frameType == FRAME_TYPE_PING) {
				fixLenPayload = true;
				verifyPingFrame();
			} else if (frameType == FRAME_TYPE_GO_AWAY) {
				fixLenPayload = false;
				verifyGoAwayFrame();
			} else if (frameType == FRAME_TYPE_WINDOW_UPDATE) {
				fixLenPayload = true;
				verifyWindowUpdateFrame();
			} else if (frameType == FRAME_TYPE_CONTINUATION) {
				fixLenPayload = false;
				verifyContinuationFrame();
			} else {
				fixLenPayload = false;
				verifyUnknownFrame();
			}

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

	private short readUByteInQueue(Queue<InputBuf> queue) {
		assert !queue.isEmpty();
		InputBuf buf = (InputBuf) queue.unsafePeek();
		assert buf.readable();
		short ret = buf.readUnsignedByte();
		if (!buf.readable()) {
			buf.release();
			queue.unsafeShift();
		}
		return ret;
	}

	public static byte readByteInQueue(Queue<InputBuf> queue) {
		assert !queue.isEmpty();
		InputBuf buf = (InputBuf) queue.unsafePeek();
		assert buf.readable();
		byte ret = buf.readByte();
		if (!buf.readable()) {
			buf.release();
			queue.unsafeShift();
		}
		return ret;
	}

	protected void readCacheBytesInQueue(Queue<InputBuf> queue, int len) {
		assert len <= this.frameReadBuffer.length && len > 0;
		assert this.frameHeaderIndex == 0;
		assert queue.isEmpty();
		InputBuf buf = queue.unsafePeek();
		assert buf != null;
		assert buf.readable();
		int ridx = 0;
		for (;;) {
			int rs = Integer.max(len, buf.readableBytes());
			buf.readBytes(this.frameReadBuffer, ridx, rs);
			len -= rs;
			if (len != 0) {
				queue.unsafeShift();
				ridx += rs;
				buf.release();
				queue.unsafeShift();
				buf = (InputBuf) queue.unsafePeek();
				assert buf != null;
				assert buf.readable();
			} else {
				break;
			}
		}
		if (!buf.readable()) {
			buf.release();
			queue.unsafeShift();
		}
	}

	private void slicePayload(int length, Queue<InputBuf> dest) {
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

	protected void readerRelease() {
		this.framePayload.free(NioAsyncChannel.RELEASE_INPUT_BUF);
		this.headersPayload.free(NioAsyncChannel.RELEASE_INPUT_BUF);
		this.dataPayload.free(NioAsyncChannel.RELEASE_INPUT_BUF);
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
		try {
			while (!this.headersPayload.isEmpty()) {
				switch (state) {
					case READ_HEADER_REPRESENTATION:
						byte b = readByteInQueue(this.headersPayload);
						--cacheHeaderLength;
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
						b = readByteInQueue(this.headersPayload);
						--cacheHeaderLength;
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
						if (cacheHeaderLength < nameLen) {
							this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
							return null;
						}
						cacheHeaderLength -= nameLen;
						name = readStringLiteral(nameLen, huffmanEncoded);
						if (null == name) {
							this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
							return null;
						}

						state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
						break;

					case READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX:
						b = readByteInQueue(this.headersPayload);
						--cacheHeaderLength;
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
						if (cacheHeaderLength < valueLen) {
							this.currentState = Http2ProtocolError.ERROR_INVALID_CONTENT_IN_HEADER_FRAME;
							return null;
						}
						cacheHeaderLength -= valueLen;
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
		} finally {
			this.headersPayload.clear(NioAsyncChannel.RELEASE_INPUT_BUF);
		}
		return headers;
	}

	private String readStringLiteral(int length, boolean huffmanEncoded) {
		if (length == 0)
			return StringUtil.EMPTY_STRING;
		if (huffmanEncoded) {
			return huffmanDecoder.decode(length);
		}

		StringBuilder sb = new StringBuilder(length);
		while (length > 0) {
			int len = Integer.min(length, this.frameReadBuffer.length);
			this.readCacheBytesInQueue(this.headersPayload, len);
			for (int i = 0; i < len; ++i) {
				sb.append((char) this.frameReadBuffer[i]);
			}
			length -= len;
		}
		return sb.toString();
	}

	public long decodeULE128(long result) {
		assert result <= 0x7f && result >= 0;
		int shift = 0;
		final boolean resultStartedAtZero = result == 0;
		while (!headersPayload.isEmpty()) {
			byte b = readByteInQueue(headersPayload);
			--cacheHeaderLength;
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
			while (length > 0) {
				int len = Integer.min(length, frameReadBuffer.length);
				readCacheBytesInQueue(headersPayload, len);
				for (int i = 0; i < len; ++i) {
					byte value = frameReadBuffer[i];
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
