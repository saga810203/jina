package org.jfw.jina.http2.impl;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
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
import org.jfw.jina.util.StringUtil;

public abstract class Http2FrameWriter extends Http2FrameReader implements FrameWriter {

	// remote Setting;

	protected boolean remoteEnablePush = false;
	protected long remoteMaxConcurrentStreams = Long.MAX_VALUE;
	protected int remoteInitialWindowSize = 65535;
	protected int remoteMaxFrameSize = 16777215;

	// local Setting
	protected long localHeaderTableSize = 4096;

	protected long sendWindowSize = 65535;

	private OutputBuf outputCache;
	private int cacheCapacity;
	private int cacheIndex;

	private Frame first;
	private Frame last;

	private Frame dataFirst;
	private Frame dataLast;

	protected Throwable lastWriteException;

	protected HpackDynamicTable localDynaTable = new HpackDynamicTable(4096);
	
	
	protected List<HpackHeaderField> enabledDynaTable = new LinkedList<HpackHeaderField>();

	protected Http2FrameWriter(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key) {
		super(executor, javaChannel, key);
		newCache();
	}

	private void newCache() {
		this.outputCache = executor.allocBuffer();
		this.cacheCapacity = this.outputCache.writableBytes();
		this.cacheIndex = 0;
	}

	private void writeFrame(Frame frame) {
		if (last == null) {
			first = frame;
			this.setOpWrite();
		} else {
			last.next = frame;
		}
		last = frame;
	}

	public boolean writeHeaders(int streamId, HttpHeaders headers, boolean endOfStream) {

		OutputBuf buf = executor.allocBuffer();
		OutputBuf headBuf = buf.keepHead(9);

		return false;

	}

	public boolean writeHeaders(int streamId, int responseStatus, HttpHeaders headers, boolean endofStream) {

		return false;
	}

	@Override
	public void writePriority(int streamId, int streamDependency, short weight, boolean exclusive) {
		assert streamId >= 0;
		assert streamDependency >= 0;
		assert streamId != streamDependency;

		if (exclusive) {
			streamDependency |= 0x80000000;
		}

		if (this.cacheCapacity - this.cacheIndex < 14) {
			this.outputCache.release();
			this.newCache();
		}

		executor.ouputCalcBuffer[0] = 0;
		executor.ouputCalcBuffer[1] = 0;
		executor.ouputCalcBuffer[2] = 5;
		executor.ouputCalcBuffer[3] = FRAME_TYPE_PRIORITY;
		executor.ouputCalcBuffer[4] = 0;
		executor.ouputCalcBuffer[5] = (byte) (streamId >>> 24);
		executor.ouputCalcBuffer[6] = (byte) (streamId >>> 16);
		executor.ouputCalcBuffer[7] = (byte) (streamId >>> 8);
		executor.ouputCalcBuffer[8] = (byte) streamId;
		executor.ouputCalcBuffer[9] = (byte) ((streamDependency >>> 24));
		executor.ouputCalcBuffer[10] = (byte) (streamDependency >>> 16);
		executor.ouputCalcBuffer[11] = (byte) (streamDependency >>> 8);
		executor.ouputCalcBuffer[12] = (byte) (streamDependency);
		executor.ouputCalcBuffer[13] = (byte) ((weight - 1) | 0xFF);

		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, 14);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_PRIORITY;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += 14;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeRstStream(int streamId, long errorCode) {
		assert streamId >= 0;
		int iec = (int) errorCode;
		if (this.cacheCapacity - this.cacheIndex < 13) {
			this.outputCache.release();
			this.newCache();
		}

		executor.ouputCalcBuffer[0] = 0;
		executor.ouputCalcBuffer[1] = 0;
		executor.ouputCalcBuffer[2] = 4;
		executor.ouputCalcBuffer[3] = FRAME_TYPE_RST_STREAM;
		executor.ouputCalcBuffer[4] = 0;
		executor.ouputCalcBuffer[5] = (byte) (streamId >>> 24);
		executor.ouputCalcBuffer[6] = (byte) (streamId >>> 16);
		executor.ouputCalcBuffer[7] = (byte) (streamId >>> 8);
		executor.ouputCalcBuffer[8] = (byte) streamId;
		executor.ouputCalcBuffer[9] = (byte) ((iec >>> 24));
		executor.ouputCalcBuffer[10] = (byte) (iec >>> 16);
		executor.ouputCalcBuffer[11] = (byte) (iec >>> 8);
		executor.ouputCalcBuffer[12] = (byte) (iec);
		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, 13);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_RST_STREAM;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += 13;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeSettings(Http2Settings setting) {
		int len = setting.writeToFrameBuffer(executor.ouputCalcBuffer, 0);
		if (this.cacheCapacity - this.cacheIndex < 13) {
			this.outputCache.release();
			this.newCache();
		}
		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, len);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_SETTINGS;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += len;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writeSettingAck() {
		if (this.cacheCapacity - this.cacheIndex < 9) {
			this.outputCache.release();
			this.newCache();
		}
		executor.ouputCalcBuffer[0] = 0;
		executor.ouputCalcBuffer[1] = 0;
		executor.ouputCalcBuffer[2] = 0;
		executor.ouputCalcBuffer[3] = FRAME_TYPE_SETTINGS;
		executor.ouputCalcBuffer[4] = Http2FlagsUtil.ack((byte) 0, true);
		executor.ouputCalcBuffer[5] = 0;
		executor.ouputCalcBuffer[6] = 0;
		executor.ouputCalcBuffer[7] = 0;
		executor.ouputCalcBuffer[8] = 0;

		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, 9);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_SETTINGS;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += 9;
		frame.next = null;
		writeFrame(frame);
	}

	@Override
	public void writePing(byte[] buffer) {
		writeFrame(buildPingFrame(buffer, false));
	}

	private Frame buildPingFrame(byte[] buffer, boolean isAck) {
		assert buffer.length >= 8;
		assert buffer != executor.ouputCalcBuffer;
		if (this.cacheCapacity - this.cacheIndex < 17) {
			this.outputCache.release();
			this.newCache();
		}
		executor.ouputCalcBuffer[0] = 0;
		executor.ouputCalcBuffer[1] = 0;
		executor.ouputCalcBuffer[2] = 0;
		executor.ouputCalcBuffer[3] = FRAME_TYPE_PING;
		executor.ouputCalcBuffer[4] = isAck ? Http2FlagsUtil.ack((byte) 0, true) : 0;
		executor.ouputCalcBuffer[5] = 0;
		executor.ouputCalcBuffer[6] = 0;
		executor.ouputCalcBuffer[7] = 0;
		executor.ouputCalcBuffer[8] = 0;
		System.arraycopy(buffer, 0, executor.ouputCalcBuffer, 9, 8);

		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, 17);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_PING;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += 17;
		frame.next = null;
		return frame;
	}

	@Override
	public void writePingAck(byte[] buffer) {
		Frame frame = buildPingFrame(buffer, true);
		Frame prev = this.first;
		Frame next = null;
		if (prev == null) {
			this.first = frame;
			this.last = frame;
			return;
		}
		for (;;) {
			next = prev.next;
			if (next == null) {
				prev.next = frame;
				last = frame;
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
		if (this.cacheCapacity - this.cacheIndex - flen >= 0) {
			this.outputCache.writeMedium(len);
			this.outputCache.writeByte(FRAME_TYPE_GO_AWAY);
			this.outputCache.writeByte(0);
			this.outputCache.writeInt(0);
			this.outputCache.writeInt(lastStreamId);
			this.outputCache.writeInt(lec);
			if (length > 0) {
				this.outputCache.writeBytes(buffer, index, len);
			}
			Frame frame = newFrame();
			frame.type = FRAME_TYPE_GO_AWAY;
			frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
			this.cacheIndex += flen;
			frame.next = null;
			writeFrame(frame);

		} else {
			OutputBuf gbuf = executor.allocBuffer();
			if ((gbuf.writableBytes() - flen) < 0) {
				throw new IllegalArgumentException("goAway Frame data to large");
			}
			gbuf.writeMedium(len);
			gbuf.writeByte(FRAME_TYPE_GO_AWAY);
			gbuf.writeByte(0);
			gbuf.writeInt(0);
			gbuf.writeInt(lastStreamId);
			gbuf.writeInt(lec);
			if (length > 0) {
				gbuf.writeBytes(buffer, index, len);
			}
			Frame frame = newFrame();
			frame.type = FRAME_TYPE_GO_AWAY;
			frame.buffer = gbuf.input();
			frame.next = null;
			gbuf.release();
			writeFrame(frame);

		}

	}

	@Override
	public void writeWindowUpdate(int streamId, int windowSizeIncrement) {
		assert windowSizeIncrement > 0;
		if (this.cacheCapacity - this.cacheIndex < 13) {
			this.outputCache.release();
			this.newCache();
		}
		executor.ouputCalcBuffer[0] = 0;
		executor.ouputCalcBuffer[1] = 0;
		executor.ouputCalcBuffer[2] = 4;
		executor.ouputCalcBuffer[3] = FRAME_TYPE_WINDOW_UPDATE;
		executor.ouputCalcBuffer[4] = 0;
		executor.ouputCalcBuffer[5] = (byte) (streamId >>> 24);
		executor.ouputCalcBuffer[6] = (byte) (streamId >>> 16);
		executor.ouputCalcBuffer[7] = (byte) (streamId >>> 8);
		executor.ouputCalcBuffer[8] = (byte) streamId;
		executor.ouputCalcBuffer[9] = (byte) ((windowSizeIncrement >>> 24));
		executor.ouputCalcBuffer[10] = (byte) (windowSizeIncrement >>> 16);
		executor.ouputCalcBuffer[11] = (byte) (windowSizeIncrement >>> 8);
		executor.ouputCalcBuffer[12] = (byte) (windowSizeIncrement);
		this.outputCache.writeBytes(executor.ouputCalcBuffer, 0, 13);
		Frame frame = this.newFrame();
		frame.type = FRAME_TYPE_WINDOW_UPDATE;
		frame.buffer = this.outputCache.input().skipBytes(this.cacheIndex);
		this.cacheIndex += 13;
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
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE;
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
				this.writeFrame(frame.next);
			}
		}
	}

	protected void writeDataFrame(Frame frame) {
		int dlen = frame.length;
		if (sendWindowSize >= dlen) {
			sendWindowSize -= dlen;
			writeFrame(frame);
		} else {
			if (dataFirst == null) {
				dataFirst = frame;
			} else {
				dataLast.next = frame;
			}
			dataLast = frame;
		}
	}

	protected void closeWriter() {
		this.outputCache.release();
		dataLast = null;
		last = null;
		Frame frame;
		while (dataFirst != null) {
			InputBuf buf = dataFirst.buffer;
			TaskCompletionHandler listenner = dataFirst.listenner;
			frame = dataFirst;
			dataFirst = frame.next;
			freeFrame(frame);
			buf.release();
			if (listenner != null) {
				listenner.failed(this.lastWriteException, executor);
			}
		}
		while (first != null) {
			InputBuf buf = first.buffer;
			TaskCompletionHandler listenner = first.listenner;
			frame = first;
			first = frame.next;
			freeFrame(frame);
			buf.release();
			if (listenner != null) {
				listenner.failed(this.lastWriteException, executor);
			}
		}
	}

	@Override
	public void write() {
		Frame frame;
		while (first != null) {
			InputBuf buf = first.buffer;
			TaskCompletionHandler listenner = first.listenner;
			try {
				buf.readBytes(this.javaChannel);
			} catch (Throwable e) {
				this.lastWriteException = e;
				this.close();
				return;
			}
			if (buf.readable()) {
				return;
			}
			frame = first;
			first = frame.next;
			freeFrame(frame);
			buf.release();
			if (listenner != null) {
				listenner.completed(executor);
			}
		}
		this.cleanOpWrite();
	}

	protected Frame newFrame() {
		return new Frame();
	}

	protected void freeFrame(final Frame frame) {
		frame.buffer = null;
		frame.listenner = null;
		frame.next = null;
	}

	public class Frame {
		byte type;
		InputBuf buffer;
		TaskCompletionHandler listenner;
		Frame next;
		int length;
	}

	final class HpackEncoder {
		private long size;
		private Frame firstHeaderFrame = null;
		private Frame currHeaderFrame = null;
		private OutputBuf headBuf = null;
		private OutputBuf currentBuf = null;
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
			this.currentBuf = executor.allocBuffer();
			this.headBuf = currentBuf.keepHead(9);
			this.encoderHeadLength = 0;
			this.currentBufLength = Integer.min(currentBuf.writableBytes(), remoteMaxFrameSize);
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
		}

		private void swichFrame() {
			boolean ff = this.firstHeaderFrame == this.currHeaderFrame;
			this.headBuf.writeMedium(this.encoderHeadLength);
			this.headBuf.writeByte(ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION);
			this.headBuf.writeByte(endOfStream ? Http2FlagsUtil.END_STREAM : 0);
			this.headBuf.writeInt(this.streamId);
			this.headBuf.release();
			this.currHeaderFrame.buffer = currentBuf.input();
			currentBuf.release();
			this.currHeaderFrame.type = ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION;
			this.currHeaderFrame.length = this.encoderHeadLength;
			Frame frame = new Frame();
			frame.next = null;
			this.currHeaderFrame.next = frame;
			this.currHeaderFrame = frame;
			this.currentBuf = executor.allocBuffer();
			this.headBuf = this.currentBuf.keepHead(9);
			this.encoderHeadLength = 0;
			this.currentBufLength = currentBuf.writableBytes();
		}

		private void endFrame() {
			boolean ff = this.firstHeaderFrame == this.currHeaderFrame;
			this.headBuf.writeMedium(this.encoderHeadLength);
			this.headBuf.writeByte(ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION);
			this.headBuf.writeByte(endOfStream ? Http2FlagsUtil.END_STREAM_END_HEADERS : Http2FlagsUtil.END_HEADERS);
			this.headBuf.writeInt(this.streamId);
			this.headBuf.release();
			this.headBuf = null;
			this.currHeaderFrame.buffer = currentBuf.input();
			currentBuf.release();
			currentBuf = null;
			this.currHeaderFrame.type = ff ? FRAME_TYPE_HEADERS : FRAME_TYPE_CONTINUATION;
			this.currHeaderFrame.length = this.encoderHeadLength;
			this.currHeaderFrame = null;
		}

		private void freeFrame() {
			Frame frame = this.firstHeaderFrame;
			while (frame != this.currHeaderFrame) {
				frame.buffer.release();
				frame.buffer = null;
				frame = frame.next;
			}
			this.headBuf.release();
			this.currentBuf.release();
			this.firstHeaderFrame = null;
			this.currHeaderFrame = null;
			this.headBuf = null;
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

			HeaderEntry headerField = getEntry(name, value);
			if (headerField != null) {
				int index = getIndex(headerField.index) + HpackStaticTable.length;
				// Section 6.1. Indexed Header Field Representation
				encodeInteger(0x80, 7, index);
			} else {
				int staticTableIndex = HpackStaticTable.getIndex(name, value);
				if (staticTableIndex != -1) {
					// Section 6.1. Indexed Header Field Representation
					encodeInteger(0x80, 7, staticTableIndex);
				} else {
					ensureCapacity();
					encodeLiteral(name, value, IndexType.INCREMENTAL, getNameIndex(name));
					add(name, value);
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
				this.currentBuf.writeByte((int) (mask | i));
			} else {
				long length = i - nbits;
				ensureOutSize(1);
				this.currentBuf.writeByte(mask | nbits);
				for (; (length & ~0x7F) != 0; length >>>= 7) {
					ensureOutSize(1);
					this.currentBuf.writeByte((int) ((length & 0x7F) | 0x80));
				}
				ensureOutSize(1);
				this.currentBuf.writeByte((int) length);
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
					this.currentBuf.writeByte(string.charAt(begin));
				}
				this.currentBufLength -= num;
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
				index = getIndex(name);
				if (index >= 0) {
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
			while (localHeaderTableSize - size < currHeadSize) {
				int index = length();
				if (index == 0) {
					break;
				}
				remove();
			}
		}



		/**
		 * Return the header field at the given index. Exposed for testing.
		 */
		HpackHeaderField getHeaderField(int index) {
			HeaderEntry entry = head;
			while (index-- >= 0) {
				entry = entry.before;
			}
			return entry;
		}

		/**
		 * Returns the header entry with the lowest index value for the header
		 * field. Returns null if header field is not in the dynamic table.
		 */
		private HeaderEntry getEntry(String name, String value) {
			if (length() == 0 || name == null || value == null) {
				return null;
			}
			int h = name.hashCode();
			int i = index(h);
			for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
				// To avoid short circuit behavior a bitwise operator is used
				// instead of a boolean operator.
				if (e.hash == h && name.equals(e.name) && value.equals(e.value)) {
					return e;
				}
			}
			return null;
		}

		/**
		 * Returns the lowest index value for the header field name in the
		 * dynamic table. Returns -1 if the header field name is not in the
		 * dynamic table.
		 */
		private int getIndex(String name) {
			if (localDynaTable.length()==0) || name == null) {
				return -1;
			}
			return localDynaTable.
			
			int h = name.hashCode();
			int i = index(h);
			for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
				if (e.hash == h && name.equals(e.name)) {
					return getIndex(e.index);
				}
			}
			return -1;
		}

		/**
		 * Compute the index into the dynamic table given the index in the
		 * header entry.
		 */
		private int getIndex(int index) {
			return index == -1 ? -1 : index - head.before.index + 1;
		}

		/**
		 * Add the header field to the dynamic table. Entries are evicted from
		 * the dynamic table until the size of the table and the new header
		 * field is less than the table's maxHeaderTableSize. If the size of the
		 * new entry is larger than the table's maxHeaderTableSize, the dynamic
		 * table will be cleared.
		 */
		private void add(String name, String value) {
			// Clear the table if the header field size is larger than the
			// maxHeaderTableSize.
			if (currHeadSize > localHeaderTableSize) {
				clear();
				return;
			}

			// Evict oldest entries until we have enough maxHeaderTableSize.
			while (localHeaderTableSize - size < currHeadSize) {
				remove();
			}

			int h = name.hashCode();
			int i = index(h);
			HeaderEntry old = headerFields[i];
			HeaderEntry e = new HeaderEntry(h, name, value, head.before.index - 1, old);
			headerFields[i] = e;
			e.addBefore(head);
			size += currHeadSize;
		}

		/**
		 * Remove and return the oldest header field from the dynamic table.
		 */
		private HpackHeaderField remove() {
			if (size == 0) {
				return null;
			}
			HeaderEntry eldest = head.after;
			int h = eldest.hash;
			int i = index(h);
			HeaderEntry prev = headerFields[i];
			HeaderEntry e = prev;
			while (e != null) {
				HeaderEntry next = e.next;
				if (e == eldest) {
					if (prev == eldest) {
						headerFields[i] = next;
					} else {
						prev.next = next;
					}
					eldest.remove();
					size -= eldest.size();
					return eldest;
				}
				prev = e;
				e = next;
			}
			return null;
		}

		/**
		 * Remove all entries from the dynamic table.
		 */
		private void clear() {
			Arrays.fill(headerFields, null);
			head.before = head.after = head;
			size = 0;
		}

		/**
		 * Returns the index into the hash table for the hash code h.
		 */
		private int index(int h) {
			return h & hashMask;
		}

	}

	/**
	 * A linked hash map HpackHeaderField entry.
	 */
	private static final class HeaderEntry extends HpackHeaderField {
		// These fields comprise the doubly linked list used for iteration.
		HeaderEntry before, after;

		// These fields comprise the chained list for header fields with the
		// same hash.
		HeaderEntry next;
		int hash;

		// This is used to compute the index in the dynamic table.
		int index;

		/**
		 * Creates new entry.
		 */
		HeaderEntry(int hash, String name, String value, int index, HeaderEntry next) {
			super(name, value);
			this.index = index;
			this.hash = hash;
			this.next = next;
		}

		/**
		 * Removes this entry from the linked list.
		 */
		private void remove() {
			before.after = after;
			after.before = before;
			before = null; // null references to prevent nepotism in
							// generational GC.
			after = null;
			next = null;
		}

		/**
		 * Inserts this entry before the specified existing entry in the list.
		 */
		private void addBefore(HeaderEntry existingEntry) {
			after = existingEntry;
			before = existingEntry.before;
			before.after = this;
			after.before = this;
		}
	}

}
