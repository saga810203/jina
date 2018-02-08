package org.jfw.jina.http2.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http2.FrameWriter;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2FlagsUtil;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.headers.HpackDynamicTable;
import org.jfw.jina.http2.headers.HpackHeaderField;
import org.jfw.jina.http2.headers.HpackStaticTable;
import org.jfw.jina.http2.headers.HpackUtil.IndexType;

public abstract class Http2FrameWriter<T extends Http2AsyncExecutor> extends Http2FrameReader<T> implements FrameWriter {

	// remote Setting;
	protected boolean remoteEnablePush = false;
	protected long remoteMaxConcurrentStreams = Long.MAX_VALUE;
	protected int remoteInitialWindowSize = 65535;
	protected int remoteMaxFrameSize = 16777215;

	// local Setting
	protected long localHeaderTableSize = 4096;

	protected long sendWindowSize = 65535;

	// private OutputBuf outputCache;
	// private int cacheCapacity;
	// private int cacheIndex;

	protected Frame firstFrame;
	protected Frame lastFrame;

	protected Frame dataFirst;
	protected Frame dataLast;

	protected Throwable lastWriteException;

	protected HpackDynamicTable localDynaTable = new HpackDynamicTable(4096);

	protected HpackEncoder hpackEncoder;

	protected Http2FrameWriter(T executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
		// newCache();
		this.hpackEncoder = new HpackEncoder();

	}

	// private void newCache() {
	// this.outputCache = executor.allocBuffer();
	// this.cacheCapacity = this.outputCache.writableBytes();
	// this.cacheIndex = 0;
	// }

	protected void writeFrame(Frame frame) {
		if (lastFrame == null) {
			firstFrame = lastFrame = frame;
			this.setOpWrite();
		} else {
			lastFrame.next = frame;
			lastFrame = frame;
		}
	}

	public boolean writeHeaders(int streamId, HttpHeaders headers, boolean endOfStream) {
		Frame frame = this.hpackEncoder.encodeHeaders(streamId, headers, endOfStream);
		if (frame == null)
			return false;
		Frame tmp = null;
		;
		while (frame != null) {
			tmp = frame.next;
			frame.next = null;
			this.writeFrame(frame);
			frame = tmp;
		}
		return true;
	}

	public boolean writeHeaders(int streamId, int responseStatus, HttpHeaders headers, boolean endOfStream) {
		Frame frame = this.hpackEncoder.encodeHeaders(streamId, responseStatus, headers, endOfStream);
		if (frame == null)
			return false;
		Frame tmp = null;
		;
		while (frame != null) {
			tmp = frame.next;
			frame.next = null;
			this.writeFrame(frame);
			frame = tmp;
		}
		return true;
	}

	protected boolean writeHeaders(int streamId, int responseStatus, HttpHeaders headers, TaskCompletionHandler task) {
		assert task != null;
		Frame frame = this.hpackEncoder.encodeHeaders(streamId, responseStatus, headers, true);
		if (frame == null)
			return false;
		Frame tail = frame;
		while (tail.next != null) {
			frame = tail.next;
			tail.next = null;
			this.writeFrame(tail);
			tail = frame;
		}
		tail.listenner = task;
		this.writeFrame(tail);
		return true;
	}

	protected Frame emptyDataFrame(int streamId) {
		// OutputBuf buf = executor.allocBuffer();
		ByteBuffer buf = ByteBuffer.allocate(9);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put(FRAME_TYPE_DATA);
		buf.put(Http2FlagsUtil.END_STREAM);
		buf.putInt(streamId);
		buf.flip();
		Frame frame = new Frame();
		frame.length = 0;
		frame.buffer = buf;
		frame.type = FRAME_TYPE_DATA;
		frame.next = null;
		return frame;
	}

	@Override
	public void writePriority(int streamId, int streamDependency, short weight, boolean exclusive) {
		assert streamId >= 0;
		assert streamDependency >= 0;
		assert streamId != streamDependency;
		if (exclusive) {
			streamDependency |= 0x80000000;
		}
		ByteBuffer buf = ByteBuffer.allocate(14);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put((byte) 5);
		buf.put(FRAME_TYPE_PRIORITY);
		buf.put((byte) 0);
		buf.putInt(streamId);
		buf.put((byte) ((weight - 1) | 0xFF));
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_PRIORITY;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeRstStream(int streamId, long errorCode) {
		assert streamId >= 0;
		int iec = (int) errorCode;
		ByteBuffer buf = ByteBuffer.allocate(13);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put((byte) 4);
		buf.put(FRAME_TYPE_RST_STREAM);
		buf.put((byte) 0);
		buf.putInt(streamId);
		buf.putInt(iec);
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_RST_STREAM;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeSettings(Http2Settings setting) {
		int len = setting.writeToFrameBuffer(executor.ouputCalcBuffer, 0);
		ByteBuffer buf = ByteBuffer.allocate(len);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put(executor.ouputCalcBuffer, 0, len);
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_SETTINGS;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeSettingAck() {
		ByteBuffer buf = ByteBuffer.allocate(9);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put(FRAME_TYPE_SETTINGS);
		buf.put(Http2FlagsUtil.ACK);
		buf.putInt(0);
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_SETTINGS;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writePing(byte[] buffer) {
		writeFrame(buildPingFrame(buffer, false));
	}

	private Frame buildPingFrame(byte[] buffer, boolean isAck) {
		ByteBuffer buf = ByteBuffer.allocate(17);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) 0);
		buf.put((byte) 0);
		buf.put((byte) 8);
		buf.put(FRAME_TYPE_PING);
		buf.put(isAck ? Http2FlagsUtil.ACK : (byte) 0);
		buf.putInt(0);
		buf.put(buffer, 0, 8);
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_PING;
		frame.buffer = buf;
		frame.next = null;
		return frame;
	}

	@Override
	public void writePingAck(byte[] buffer) {
		Frame frame = buildPingFrame(buffer, true);
		Frame prev = this.firstFrame;
		Frame next = null;
		if (prev == null) {
			this.firstFrame = frame;
			this.lastFrame = frame;
			return;
		}
		for (;;) {
			next = prev.next;
			if (next == null) {
				prev.next = frame;
				lastFrame = frame;
				return;
			}
			if (next.type == FRAME_TYPE_CONTINUATION) {
				prev = next;
			} else {
				prev.next = frame;
				frame.next = next;
				return;
			}
		}
	}

	@Override
	public void writeGoAway(int lastStreamId, long errorCode, byte[] buffer, int index, int length) {
		assert lastStreamId >= 0;
		assert buffer != null;
		assert index >= 0;
		assert index <= buffer.length;
		assert length >= 0;
		assert index + length >= buffer.length;
		int lec = (int) errorCode;
		int len = 4 + 4 + length;
		int flen = len + 9;
		ByteBuffer buf = ByteBuffer.allocate(flen);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte) (len >>> 16));
		buf.put((byte) (len >>> 8));
		buf.put((byte) len);
		buf.put(FRAME_TYPE_GO_AWAY);
		buf.put((byte) 0);
		buf.putInt(0);
		buf.putInt(lastStreamId);
		buf.putInt(lec);
		if (length > 0) {
			buf.put(buffer, index, len);
		}
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_GO_AWAY;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeWindowUpdate(int streamId, int windowSizeIncrement) {
		assert windowSizeIncrement > 0;
		ByteBuffer buf = ByteBuffer.allocate(13);
		buf.order(ByteOrder.BIG_ENDIAN);
		buf.put((byte)0);
		buf.put((byte)0);
		buf.put((byte)4);
		buf.put(FRAME_TYPE_WINDOW_UPDATE);
		buf.put((byte) 0);
		buf.putInt(streamId);
		buf.putInt(windowSizeIncrement);
		buf.flip();
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_WINDOW_UPDATE;
		frame.buffer = buf;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void recvSettingAck() {
	}

	@Override
	public void recvPingAck(byte[] buffer) {
	}

	public void windowUpdate(int size) {
		if (this.sendWindowSize + size > 2147483647) {
			currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE;
			return;
		}
		this.sendWindowSize += size;
		Frame frame = null;
		for (;;) {
			if (dataFirst == null) {
				dataLast = null;
				return;
			}
			int dlen = dataFirst.length;
			if (this.sendWindowSize >= dlen) {
				this.sendWindowSize -= dlen;
				frame = dataFirst;
				dataFirst = dataFirst.next;
				frame.next = null;
				this.writeFrame(frame);
			}
		}
	}

	public void writeDataFrame(Frame frame) {
		if (dataLast != null) {
			dataLast.next = frame;
			dataLast = frame;
			return;
		}
		int dlen = frame.length;
		if (sendWindowSize >= dlen) {
			sendWindowSize -= dlen;
			writeFrame(frame);
		} else {
			if (dataFirst == null) {
				dataFirst = dataLast = frame;
			} else {
				dataLast.next = frame;
				dataLast = frame;
			}
		}
	}

	protected void closeWriter() {

		dataLast = null;
		lastFrame = null;
		Frame frame;
		while (dataFirst != null) {
			TaskCompletionHandler listenner = dataFirst.listenner;
			frame = dataFirst;
			dataFirst = frame.next;
			freeFrame(frame);
			if (listenner != null) {
				executor.safeInvokeFailed(listenner,this.writeException);

			}
		}
		while (firstFrame != null) {
			TaskCompletionHandler listenner = firstFrame.listenner;
			frame = firstFrame;
			firstFrame = frame.next;
			freeFrame(frame);
			if (listenner != null) {
				executor.safeInvokeFailed(listenner,this.writeException);
			}
		}
	}

	@Override
	public void write() {
		Frame frame;
		while (firstFrame != null) {
			ByteBuffer buf = firstFrame.buffer;
			TaskCompletionHandler listenner = firstFrame.listenner;
			try {
				this.javaChannel.write(buf);
			} catch (Throwable e) {
				this.lastWriteException = e;
				this.close();
				return;
			}
			if (buf.hasRemaining()) {
				return;
			}
			frame = firstFrame;
			firstFrame = frame.next;
			freeFrame(frame);
			if (listenner != null) {
				executor.safeInvokeCompleted(frame.listenner);
			}
		}
		lastFrame = null;
		this.cleanOpWrite();
	}

	protected void writeCloseFrame() {
		Frame frame = new Frame();
		frame.buffer = EMPTY_BUFFER;
		frame.length = 0;
		frame.listenner = new TaskCompletionHandler() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
				close();
			}
			@Override
			public void completed(AsyncExecutor executor) {
				close();
			}
		};
		this.writeFrame(frame);
	}

	protected Frame newFrame() {
		return new Frame();
	}

	protected void freeFrame(final Frame frame) {
		frame.buffer = null;
		frame.listenner = null;
		frame.next = null;
	}

	public Frame buildDataFrame(int streamId, byte[] buffer, int index, int length, boolean endOfStream) {
		Frame ret = new Frame();
		Frame curr = ret;
		curr.next = null;
		ByteBuffer buf= ByteBuffer.allocate(8192); 
		buf.order(ByteOrder.BIG_ENDIAN);
		for (;;) {
			int payloadLength = Integer.min(length, 8192-9);
			buf.put(executor.ouputCalcBuffer, 0,9);
			buf.put(buffer, index, payloadLength);
			length -= payloadLength;
			boolean ok = length == 0;
			int head = (payloadLength<<8)| FRAME_TYPE_DATA;
			buf.putInt(0,head);
			buf.put(4,ok?(endOfStream?Http2FlagsUtil.END_STREAM:0):0);
			buf.putInt(5,streamId);
			curr.type = FRAME_TYPE_DATA;
			curr.length = payloadLength;
			if (ok) {
				return ret;
			} else {
				index += payloadLength;
				curr.next = new Frame();
				curr = curr.next;
				buf= ByteBuffer.allocate(8192); 
				buf.order(ByteOrder.BIG_ENDIAN);
			}
		}
	}

	public static class Frame {
		byte type;
		ByteBuffer buffer;
		public TaskCompletionHandler listenner;
		public Frame next;
		public int length;
	}

	final class HpackEncoder {
		private Frame firstHeaderFrame = null;
		private Frame currHeaderFrame = null;
		private ByteBuffer currentBuf = null;
		private int encoderHeadLength = 0;
		private boolean endOfStream = false;
		private int currentBufLength = 0;
		private int streamId = 0;
		private long headTotalSize = 0;
		private long currHeadSize = 0;

		private void reset() {
			this.firstHeaderFrame = new Frame();
			this.currHeaderFrame = this.firstHeaderFrame;
			this.currHeaderFrame.next = null;
			this.currentBuf =ByteBuffer.allocate(8192);
			this.currentBuf.order(ByteOrder.BIG_ENDIAN);
			this.currentBuf.put(frameReadBuffer,0,9);
			this.encoderHeadLength = 0;
			this.currentBufLength =8192-9;
			this.headTotalSize = 0;
		}

		private void ensureOutSize(int length) {
			if (this.currentBufLength < length) {
				this.swichFrame();
			}
			if (this.currentBufLength < length) {
				throw new UnsupportedOperationException("header invalid(name or value is too large)");
			}
			this.currentBufLength -= length;
			this.encoderHeadLength += length;
		}

		private void swichFrame() {
			boolean ff = this.firstHeaderFrame == this.currHeaderFrame;
			int head = (this.encoderHeadLength << 8) | (ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION);
			this.currentBuf.putInt(0,head);
			this.currentBuf.put(4,(byte)0);
			
			this.currentBuf.putInt(5,this.streamId);
			this.currentBuf.flip();
			this.currHeaderFrame.buffer =currentBuf;
			this.currHeaderFrame.type = ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION;
			this.currHeaderFrame.length = this.encoderHeadLength;
			Frame frame = new Frame();
			frame.next = null;
			this.currHeaderFrame.next = frame;
			this.currHeaderFrame = frame;
			this.currentBuf =ByteBuffer.allocate(8192);
			this.currentBuf.order(ByteOrder.BIG_ENDIAN);
			this.currentBuf.put(frameReadBuffer,0,9);
			this.encoderHeadLength = 0;
			this.currentBufLength = Integer.min(8192-9, remoteMaxFrameSize);

		}

		private void endFrame() {
			boolean ff = this.firstHeaderFrame == this.currHeaderFrame;
			int head = (this.encoderHeadLength << 8) | (ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION);
			this.currentBuf.putInt(0,head);
			this.currentBuf.put(4,endOfStream ? Http2FlagsUtil.END_STREAM_END_HEADERS : Http2FlagsUtil.END_HEADERS);
			this.currentBuf.putInt(5,this.streamId);
			this.currentBuf.flip();
			this.currHeaderFrame.buffer =currentBuf;
			this.currHeaderFrame.type = ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION;
			this.currHeaderFrame.length = this.encoderHeadLength;
			this.currHeaderFrame = null;
		}

		private void freeFrame() {
			Frame frame = this.firstHeaderFrame;
			while (frame != this.currHeaderFrame) {
				frame.buffer = null;
				frame = frame.next;
			}
			this.currHeaderFrame = null;
			this.currentBuf = null;
		}

		public Frame encodeHeaders(int streamId, HttpHeaders headers, boolean endOfStream) {
			this.streamId = streamId;
			this.endOfStream = endOfStream;
			reset();
			// To ensure we stay consistent with our peer check the size is
			// valid before we potentially modify HPACK state.
			for (Map.Entry<String, String> header : headers) {
				String name = header.getKey();
				String value = header.getValue();
				// OK to increment now and check for bounds after because this
				// value is limited to unsigned int and will not
				// overflow.
				currHeadSize = name.length() + value.length() + HpackHeaderField.HEADER_ENTRY_OVERHEAD;
				headTotalSize += currHeadSize;
				if (headTotalSize > maxHeaderListSize) {
					this.freeFrame();
					return null;
				}
				encodeHeader(name, value);

			}
			this.endFrame();
			Frame ret = this.firstHeaderFrame;
			this.firstHeaderFrame = null;
			return ret;
		}

		public Frame encodeHeaders(int streamId, int status, HttpHeaders headers, boolean endOfStream) {
			this.streamId = streamId;
			this.endOfStream = endOfStream;
			reset();
			// To ensure we stay consistent with our peer check the size is
			// valid before we potentially modify HPACK state.
			String name = ":status";
			String value = Integer.toString(status);
			currHeadSize = name.length() + value.length() + HpackHeaderField.HEADER_ENTRY_OVERHEAD;
			headTotalSize += currHeadSize;
			if (headTotalSize > maxHeaderListSize) {
				this.freeFrame();
				return null;
			}
			encodeHeader(name, value);

			for (Map.Entry<String, String> header : headers) {
				name = header.getKey();
				value = header.getValue();
				// OK to increment now and check for bounds after because this
				// value is limited to unsigned int and will not
				// overflow.
				currHeadSize = name.length() + value.length() + HpackHeaderField.HEADER_ENTRY_OVERHEAD;
				headTotalSize += currHeadSize;
				if (headTotalSize > maxHeaderListSize) {
					this.freeFrame();
					return null;
				}
				encodeHeader(name, value);
			}
			this.endFrame();
			Frame ret = this.firstHeaderFrame;
			this.firstHeaderFrame = null;
			return ret;
		}

		/**
		 * Encode the header field into the header block.
		 *
		 * <strong>The given {@link CharSequence}s must be immutable!</strong>
		 */
		private void encodeHeader(String name, String value) {
			// If the headerSize is greater than the max table size then it must
			// be encoded literally
			if (currHeadSize > localHeaderTableSize) {
				int nameIndex = getNameIndex(name);
				encodeLiteral(name, value, IndexType.NONE, nameIndex);
				return;
			}

			int index = localDynaTable.getIndex(name, value);
			if (index != 0) {
				index += HpackStaticTable.length;
				// Section 6.1. Indexed Header Field Representation
				encodeInteger(0x80, 7, index);
			} else {
				int staticTableIndex = HpackStaticTable.getIndex(name, value);
				if (staticTableIndex != -1) {
					// Section 6.1. Indexed Header Field Representation
					encodeInteger(0x80, 7, staticTableIndex);
				} else {
					ensureCapacity();
					IndexType it = isAppendToHeaderTable(name, value) ? IndexType.INCREMENTAL : IndexType.NONE;
					if (it == IndexType.INCREMENTAL) {
						ensureCapacity();
					}
					encodeLiteral(name, value, it, getNameIndex(name));
					if (it == IndexType.INCREMENTAL) {
						localDynaTable.add(new HpackHeaderField(name, value));
					}
				}
			}
		}

		/**
		 * Encode integer according to
		 * <a href="https://tools.ietf.org/html/rfc7541#section-5.1">Section
		 * 5.1</a>.
		 */
		private void encodeInteger(int mask, int n, long i) {
			assert n >= 0 && n <= 8 : "N: " + n;
			int nbits = 0xFF >>> (8 - n);
			if (i < nbits) {
				ensureOutSize(1);
				this.currentBuf.put((byte) (mask | i));
			} else {
				long length = i - nbits;
				ensureOutSize(1);
				this.currentBuf.put((byte)(mask | nbits));
				for (; (length & ~0x7F) != 0; length >>>= 7) {
					ensureOutSize(1);
					this.currentBuf.put((byte) ((length & 0x7F) | 0x80));
				}
				ensureOutSize(1);
				this.currentBuf.put((byte) length);
			}
		}

		/**
		 * Encode string literal according to Section 5.2.
		 */
		private void encodeStringLiteral(String string) {
			encodeInteger(0x00, 7, string.length());
			int end = string.length();
			int begin = 0;
			int num = 0;
			for (;;) {
				if (this.currentBufLength == 0) {
					this.swichFrame();
				}
				num = Integer.min(end - begin, this.currentBufLength);
				for (int i = 0; i < num; ++begin, ++i) {
					this.currentBuf.put((byte)string.charAt(begin));
				}
				this.currentBufLength -= num;
				this.encoderHeadLength += num;
				if (begin >= end)
					break;
			}
		}

		/**
		 * Encode literal header field according to Section 6.2.
		 */
		private void encodeLiteral(String name, String value, IndexType indexType, int nameIndex) {
			boolean nameIndexValid = nameIndex != -1;
			switch (indexType) {
				case INCREMENTAL:
					encodeInteger(0x40, 6, nameIndexValid ? nameIndex : 0);
					break;
				case NONE:
					encodeInteger(0x00, 4, nameIndexValid ? nameIndex : 0);
					break;
				case NEVER:
					encodeInteger(0x10, 4, nameIndexValid ? nameIndex : 0);
					break;
				default:
					throw new Error("should not reach here");
			}
			if (!nameIndexValid) {
				encodeStringLiteral(name);
			}
			encodeStringLiteral(value);
		}

		private int getNameIndex(String name) {
			int index = HpackStaticTable.getIndex(name);
			if (index == -1) {
				index = localDynaTable.getIndex(name);
				if (index > 0) {
					index += HpackStaticTable.length;
				}
			}
			return index;
		}

		/**
		 * Ensure that the dynamic table has enough room to hold 'headerSize'
		 * more bytes. Removes the oldest entry from the dynamic table until
		 * sufficient space is available.
		 */
		private void ensureCapacity() {
			long size = 0;
			for (;;) {
				size = localDynaTable.size();
				if (localHeaderTableSize - size < currHeadSize) {
					if (localDynaTable.length() > 0) {
						localDynaTable.remove();
					}
				} else {
					return;
				}
			}
		}
	}
}
