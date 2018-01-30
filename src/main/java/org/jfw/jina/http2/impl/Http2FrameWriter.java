package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.core.impl.NioAsyncExecutor;
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

	protected HpackEncoder hpackEncoder;

	protected Http2FrameWriter(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key) {
		super(executor, javaChannel, key);
		newCache();
		this.hpackEncoder = new HpackEncoder();

	}

	private void newCache() {
		this.outputCache = executor.allocBuffer();
		this.cacheCapacity = this.outputCache.writableBytes();
		this.cacheIndex = 0;
	}

	protected void writeFrame(Frame frame) {
		if (last == null) {
			first = frame;
			this.setOpWrite();
		} else {
			last.next = frame;
		}
		last = frame;
	}

	public boolean writeHeaders(int streamId, HttpHeaders headers, boolean endOfStream) {
		Frame frame = this.hpackEncoder.encodeHeaders(streamId, headers, endOfStream);
		if (frame == null)
			return false;
		while (frame != null) {
			this.writeFrame(frame);
			frame = frame.next;
		}
		return true;
	}

	public boolean writeHeaders(int streamId, int responseStatus, HttpHeaders headers, boolean endOfStream) {
		Frame frame = this.hpackEncoder.encodeHeaders(streamId, responseStatus, headers, endOfStream);
		if (frame == null)
			return false;
		while (frame != null) {
			this.writeFrame(frame);
			frame = frame.next;
		}
		return true;
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
				this.writeFrame(frame);
			}
		}
	}

	public void writeDataFrame(Frame frame) {
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
				NioAsyncExecutor.safeInvokeCompleted(frame.listenner, executor);
			}
		}
		this.cleanOpWrite();
	}

	protected void writeCloseFrame() {
		Frame frame = new Frame();
		frame.buffer = EmptyBuf.INSTANCE;
		frame.length = 0;
		frame.listenner = new TaskCompletionHandler() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
			}
			@Override
			public void completed(AsyncExecutor executor) {
				close();
			}
		};
	}

	protected Frame newFrame() {
		return new Frame();
	}

	protected void freeFrame(final Frame frame) {
		frame.buffer = null;
		frame.listenner = null;
		frame.next = null;
	}
	public Frame buildDataFrame(int streamId,byte[] buffer,int index,int length,boolean endOfStream){
		Frame ret = new Frame();
		Frame curr = ret;
		curr.next = null;
		OutputBuf buf = executor.allocBuffer();
		OutputBuf head = buf.keepHead(9);
		for(;;){
			int payloadLength = Integer.min(length,buf.writableBytes());
			buf.writeBytes(buffer, index, payloadLength);
			length-=payloadLength;
			boolean ok = length==0;
			head.writeMedium(payloadLength);
			head.writeByte(FRAME_TYPE_DATA);
			head.writeByte(ok?(endOfStream?Http2FlagsUtil.END_STREAM:0):0);
			head.writeInt(streamId);
			head.release();
			curr.buffer = buf.input();
			buf.release();
			curr.type = FRAME_TYPE_DATA;
			curr.length = payloadLength;
			if(ok){
				return ret;
			}else{
				index+=payloadLength;
				curr.next = new Frame();
				curr = curr.next;
				buf = executor.allocBuffer();
				head = buf.keepHead(9);
			}
		}
	}
	
	

	public class Frame {
		byte type;
		InputBuf buffer;
		public TaskCompletionHandler listenner;
		public Frame next;
		public int length;
	}

	final class HpackEncoder {
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
			this.encoderHeadLength += length;
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
