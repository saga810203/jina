package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ScatteringByteChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;

class HeadOutputBuf implements OutputBuf {
	ByteBuffer buffer;
	int writerIndex;
	Object next;
	int capacity;

	public HeadOutputBuf(DirectOutputBuf buf, int capacity) {
		assert buf != null && capacity > 0 && capacity <= buf.buffer.capacity();
		this.writerIndex = 0;
		this.buffer = buf.buffer;
		this.next = buf;
		this.capacity = capacity;
	}

	@Override
	public int writableBytes() {
		assert next != null && ((DirectOutputBuf) next).alloc.executor().inLoop() && this.buffer != null;
		return this.capacity - this.writerIndex;
	}

	@Override
	public boolean writable() {
		assert next != null && ((DirectOutputBuf) next).alloc.executor().inLoop() && this.buffer != null;
		return this.capacity > this.writerIndex;
	}

	@Override
	public boolean writable(int size) {
		assert next != null && ((DirectOutputBuf) next).alloc.executor().inLoop() && this.buffer != null;
		;
		assert size > 0;
		return this.capacity - this.writerIndex >= size;
	}

	@Override
	public HeadOutputBuf writeBoolean(boolean value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) (value ? 1 : 0));
		return this;
	}

	@Override
	public HeadOutputBuf writeByte(int value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public HeadOutputBuf writeShort(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public HeadOutputBuf writeShortLE(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, Short.reverseBytes((short) value));
		this.writerIndex += 2;
		return this;
	}

	@Override
	public HeadOutputBuf writeMedium(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public HeadOutputBuf writeMediumLE(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) value);
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		return this;
	}

	@Override
	public HeadOutputBuf writeInt(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, value);
		this.writerIndex += 4;
		return this;
	}

	@Override
	public HeadOutputBuf writeIntLE(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, Integer.reverseBytes(value));
		this.writerIndex += 4;
		return this;
	}

	@Override
	public HeadOutputBuf writeLong(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, value);
		this.writerIndex += 8;
		return this;
	}

	@Override
	public HeadOutputBuf writeLongLE(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, Long.reverseBytes(value));
		this.writerIndex += 8;
		return this;
	}

	@Override
	public HeadOutputBuf writeChar(int value) {
		assert writable(2);
		buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public HeadOutputBuf writeFloat(float value) {
		return writeInt(Float.floatToRawIntBits(value));
	}

	@Override
	public HeadOutputBuf writeFloatLE(float value) {
		return writeIntLE(Float.floatToRawIntBits(value));
	}

	@Override
	public HeadOutputBuf writeDouble(double value) {
		return writeLong(Double.doubleToRawLongBits(value));
	}

	@Override
	public HeadOutputBuf writeDoubleLE(double value) {
		return writeLongLE(Double.doubleToRawLongBits(value));
	}

	@Override
	public HeadOutputBuf writeBytes(byte[] src) {
		return this.writeBytes(src, 0, src.length);
	}

	@Override
	public HeadOutputBuf writeBytes(byte[] src, int srcIndex, int length) {
		assert src != null && srcIndex >= 0 && length > 0 && srcIndex + length <= srcIndex && writable(length);
		buffer.mark();
		try {
			buffer.position(this.writerIndex);
			buffer.put(src, srcIndex, length);
			this.writerIndex += length;
		} finally {
			buffer.reset();
		}
		return this;
	}

	@Override
	public int writeBytes(ScatteringByteChannel in) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int writeBytes(FileChannel in, long position, int length) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public DirectOutputBuf retain() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void release() {
		assert null != next && next instanceof DirectOutputBuf && ((DirectOutputBuf) next).alloc.executor().inLoop();
		if (next != null) {
			((DirectOutputBuf) next).release();
			next = null;
			buffer = null;
		}
	}

	@Override
	public InputBuf input() {
		throw new UnsupportedOperationException();
	}

	@Override
	public OutputBuf keepHead(int size) {
		assert next != null && ((DirectOutputBuf) next).alloc.executor().inLoop() && size > 0 && size < this.capacity
				&& this.writerIndex == 0;
		((DirectOutputBuf) next).retain();
		this.writerIndex+=size;
		return new HeadOutputBuf((DirectOutputBuf) next, size);
	}

	@Override
	public long size() {
		assert next != null && ((DirectOutputBuf) next).alloc.executor().inLoop();
		return this.writerIndex;
	}

}
