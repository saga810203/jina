package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.ScatteringByteChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;

class DirectOutputBuf implements OutputBuf {
	int refCnt;
	final ByteBuffer buffer;
	int writerIndex;
	final DirectAllocator alloc;
	Object next;
	int capacity;

	DirectOutputBuf(DirectAllocator alloc, ByteBuffer buffer) {
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
	public DirectOutputBuf writeBoolean(boolean value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) (value ? 1 : 0));
		return this;
	}

	@Override
	public DirectOutputBuf writeByte(int value) {
		assert writable(1);
		this.buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public DirectOutputBuf writeShort(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectOutputBuf writeShortLE(int value) {
		assert writable(2);
		this.buffer.putShort(this.writerIndex, Short.reverseBytes((short) value));
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectOutputBuf writeMedium(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public DirectOutputBuf writeMediumLE(int value) {
		assert writable(3);
		buffer.put(this.writerIndex++, (byte) value);
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		return this;
	}

	@Override
	public DirectOutputBuf writeInt(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, value);
		this.writerIndex += 4;
		return this;
	}

	@Override
	public DirectOutputBuf writeIntLE(int value) {
		assert writable(4);
		buffer.putInt(this.writerIndex, Integer.reverseBytes(value));
		this.writerIndex += 4;
		return this;
	}

	@Override
	public DirectOutputBuf writeLong(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, value);
		this.writerIndex += 8;
		return this;
	}

	@Override
	public DirectOutputBuf writeLongLE(long value) {
		assert writable(8);
		buffer.putLong(this.writerIndex, Long.reverseBytes(value));
		this.writerIndex += 8;
		return this;
	}

	@Override
	public DirectOutputBuf writeChar(int value) {
		assert writable(2);
		buffer.putShort(this.writerIndex, (short) value);
		this.writerIndex += 2;
		return this;
	}

	@Override
	public DirectOutputBuf writeFloat(float value) {
		return writeInt(Float.floatToRawIntBits(value));
	}

	@Override
	public DirectOutputBuf writeFloatLE(float value) {
		return writeIntLE(Float.floatToRawIntBits(value));
	}

	@Override
	public DirectOutputBuf writeDouble(double value) {
		return writeLong(Double.doubleToRawLongBits(value));
	}

	@Override
	public DirectOutputBuf writeDoubleLE(double value) {
		return writeLongLE(Double.doubleToRawLongBits(value));
	}

	@Override
	public DirectOutputBuf writeBytes(byte[] src) {
		return this.writeBytes(src, 0, src.length);
	}

	@Override
	public DirectOutputBuf writeBytes(byte[] src, int srcIndex, int length) {
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
	public DirectOutputBuf retain() {
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
		((DirectOutputBuf) next).retain();
		this.writerIndex+=size;
		return new HeadOutputBuf((DirectOutputBuf) next, size);
	}
	@Override
	public long size() {
		assert alloc.executor().inLoop();
		return writerIndex;
	}
	
    

}
