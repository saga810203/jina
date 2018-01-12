package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.ScatteringByteChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;

class DirectByteOutputBuf implements OutputBuf {
	int refCnt;
	final ByteBuffer buffer;
	int writerIndex;
	final DirectAllocator alloc;
	Object next;
	int capacity;

	DirectByteOutputBuf(DirectAllocator alloc, ByteBuffer buffer) {
		this.alloc = alloc;
		this.buffer = buffer;
		this.buffer.order(ByteOrder.BIG_ENDIAN);
		this.capacity = buffer.capacity();
		this.refCnt = 1;
		this.writerIndex = 0;
	}
	ByteBuffer buffer() {
		return buffer;
	}
	@Override
	public int writableBytes() {
		assert alloc.executor().inLoop()&& this.next==null;
		return this.capacity - this.writerIndex;
	}
	@Override
	public boolean writable() {
		assert alloc.executor().inLoop() && this.next==null;
		return this.capacity > this.writerIndex;
	}

	@Override
	public boolean writable(int size) {
		assert alloc.executor().inLoop() && this.next ==null;;
		assert size > 0;
		return this.capacity - this.writerIndex >= size;
	}
	
	@Override
	public DirectByteOutputBuf writeBoolean(boolean value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) (value ? 1 : 0));
		return this;
	}

	@Override
	public DirectByteOutputBuf writeByte(int value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public DirectByteOutputBuf writeShort(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeShortLE(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, Short.reverseBytes((short) value));
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeMedium(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public DirectByteOutputBuf writeMediumLE(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) value);
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		return this;
	}

	@Override
	public DirectByteOutputBuf writeInt(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, value);
		this.writerIndex += 4;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeIntLE(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, Integer.reverseBytes(value));
		this.writerIndex += 4;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeLong(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, value);
		this.writerIndex += 8;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeLongLE(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, Long.reverseBytes(value));
		this.writerIndex += 8;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeChar(int value) {
		assert writable(2);
		buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectByteOutputBuf writeFloat(float value) {
		return writeInt(Float.floatToRawIntBits(value));
	}

	@Override
	public DirectByteOutputBuf writeFloatLE(float value) {
		return writeIntLE(Float.floatToRawIntBits(value));
	}

	@Override
	public DirectByteOutputBuf writeDouble(double value) {
		return writeLong(Double.doubleToRawLongBits(value));
	}

	@Override
	public DirectByteOutputBuf writeDoubleLE(double value) {
		return writeLongLE(Double.doubleToRawLongBits(value));
	}

	@Override
	public DirectByteOutputBuf writeBytes(byte[] src) {
		return this.writeBytes(src, 0, src.length);
	}

	@Override
	public DirectByteOutputBuf writeBytes(byte[] src, int srcIndex, int length) {
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
		assert writable();
		buffer.mark();
		try {
			buffer.position(this.writerIndex);
			int ret = in.read(buffer);
			if (ret > 0) {
				this.writerIndex += ret;
			}
			return ret;
		} finally {
			buffer.reset();
		}
	}

	@Override
	public int writeBytes(FileChannel in, long position, int length) throws IOException {
		assert in != null && position >= 0 && writable(length);
		in.position(position);
		buffer.mark();
		try {
			buffer.position(this.writerIndex);
			int ret = in.read(buffer);
			if (ret > 0) {
				this.writerIndex += ret;
			}
			return ret;
		} finally {
			buffer.reset();
		}
	}



	@Override
	public DirectByteOutputBuf retain() {
		assert alloc.executor().inLoop();
		++refCnt;
		return this;
	}

	@Override
	public void release() {
		assert alloc.executor().inLoop();
		--refCnt;
		if (refCnt == 0) {
			this.writerIndex = 0;
			this.buffer.clear();
		    alloc.release(this);
		}
	}

	@Override
	public InputBuf input() {
		assert alloc.executor().inLoop()&& refCnt==1 && this.writerIndex >0 && this.next ==null;
		++refCnt;
		this.next = new DirectInputBuf(this, this.writerIndex);
		return (DirectInputBuf) this.next;
	}
	@Override
	public HeadOutputBuf keepHead(int size) {
		assert alloc.executor().inLoop()  && size > 0 && size < this.capacity 	&& this.writerIndex == 0;
		((DirectByteOutputBuf) next).retain();
		this.writerIndex+=size;
		return new HeadOutputBuf((DirectByteOutputBuf) next, size);
	}
	@Override
	public long size() {
		assert alloc.executor().inLoop();
		return writerIndex;
	}
	
    

}
