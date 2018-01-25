package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http2.FrameWriter;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2FlagsUtil;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.http2.Http2Settings;

public abstract class Http2FrameWriter extends Http2FrameReader implements FrameWriter {
	//remote Setting;
	protected int remoteHeaderTableSize = 4096;
	protected boolean remoteEnablePush = false;
	protected long remoteMaxConcurrentStreams=Long.MAX_VALUE;
	protected int remoteInitialWindowSize=65535;
	protected int remoteMaxFrameSize = 16777215;
	protected long remoteMaxHeaderListSize = Long.MAX_VALUE; 
	
	protected long sendWindowSize = 65535;

	

	private OutputBuf outputCache;
	private int cacheCapacity;
	private int cacheIndex;

	private Frame first;
	private Frame last;

	private Frame dataFirst;
	private Frame dataLast;

	protected Throwable lastWriteException;

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

	@Override
	public void writeResponseHeader(int streamId, int stauts, HttpHeaders headers, boolean endStream) {
		// TODO Auto-generated method stub

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
}
