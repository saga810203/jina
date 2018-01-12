package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.ScatteringByteChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;

class CompositeOutputBuf implements OutputBuf {
	private long size;
	private final DirectByteOutputBuf first;
	private DirectByteOutputBuf cur;

	public CompositeOutputBuf(DirectByteOutputBuf first) {
		this.first = first;
		this.cur = first;
		this.size = 0;
	}

	@Override
	public boolean writable() {
		return true;
	}

	@Override
	public boolean writable(int size) {
		return true;
	}

	@Override
	public int writableBytes() {
		return Integer.MAX_VALUE;
	}

	@Override
	public OutputBuf writeBoolean(boolean value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable()) {
			cur.writeBoolean(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeBoolean(value);
			cur.next = nd;
			cur = nd;
		}
		++size;
		return this;
	}

	@Override
	public OutputBuf writeByte(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable()) {
			cur.writeByte(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeByte(value);
			cur.next = nd;
			cur = nd;
		}
		++size;
		return this;
	}

	@Override
	public OutputBuf writeShort(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(2)) {
			cur.writeShort(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeShort(value);
			cur.next = nd;
			cur = nd;
		}
		size+=2;
		return this;
	}

	@Override
	public OutputBuf writeShortLE(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(2)) {
			cur.writeShortLE(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeShortLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=2;
		return this;
	}

	@Override
	public OutputBuf writeMedium(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(3)) {
			cur.writeMedium(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeMedium(value);
			cur.next = nd;
			cur = nd;
		}
		size+=3;
		return this;
	}

	@Override
	public OutputBuf writeMediumLE(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(3)) {
			cur.writeMediumLE(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeMediumLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=3;
		return this;
	}

	@Override
	public OutputBuf writeInt(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(4)) {
			cur.writeInt(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeInt(value);
			cur.next = nd;
			cur = nd;
		}
		size+=4;
		return this;
	}

	@Override
	public OutputBuf writeIntLE(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(4)) {
			cur.writeInt(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeIntLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=4;
		return this;
	}

	@Override
	public OutputBuf writeLong(long value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(8)) {
			cur.writeLong(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeLong(value);
			cur.next = nd;
			cur = nd;
		}
		size+=8;
		return this;
	}

	@Override
	public OutputBuf writeLongLE(long value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(8)) {
			cur.writeLongLE(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeLongLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=8;
		return this;
	}

	@Override
	public OutputBuf writeChar(int value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(2)) {
			cur.writeChar(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeChar(value);
			cur.next = nd;
			cur = nd;
		}
		size+=2;
		return this;
	}

	@Override
	public OutputBuf writeFloat(float value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(4)) {
			cur.writeFloat(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeFloat(value);
			cur.next = nd;
			cur = nd;
		}
		size+=4;
		return this;
	}

	@Override
	public OutputBuf writeFloatLE(float value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(4)) {
			cur.writeFloatLE(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeFloatLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=4;
		return this;
	}

	@Override
	public OutputBuf writeDouble(double value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(8)) {
			cur.writeDouble(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeDouble(value);
			cur.next = nd;
			cur = nd;
		}
		size+=8;
		return this;
	}

	@Override
	public OutputBuf writeDoubleLE(double value) {
		assert first.alloc.executor().inLoop();
		if (cur.writable(8)) {
			cur.writeDoubleLE(value);
		} else {
			DirectByteOutputBuf nd = first.alloc.buffer();
			nd.writeDoubleLE(value);
			cur.next = nd;
			cur = nd;
		}
		size+=8;
		return this;
	}

	@Override
	public OutputBuf writeBytes(byte[] src) {
		assert first.alloc.executor().inLoop() && src != null && src.length > 0;
		return this.writeBytes(src, 0, src.length);
	}

	@Override
	public OutputBuf writeBytes(byte[] src, int srcIndex, int length) {
		assert first.alloc.executor().inLoop() && src != null && srcIndex >= 0 && length > srcIndex && srcIndex + length < src.length;
		for (;;) {
			int len = cur.writableBytes();
			if (length <= len) {
				cur.writeBytes(src, srcIndex, length);
				size+=length;
				return this;
			} else {
				if (len > 0) {
					cur.writeBytes(src, srcIndex, len);
					length -= len;
					srcIndex += len;
					size+=len;
				}
				DirectByteOutputBuf nd = first.alloc.buffer();
				cur.next = nd;
				cur = nd;
			}
		}
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
	public HeadOutputBuf keepHead(int size) {
		return first.keepHead(size);
	}

	@Override
	public CompositeOutputBuf retain() {
		DirectByteOutputBuf buf = this.first;
		while(buf!=null){
			++buf.refCnt;
			buf = (DirectByteOutputBuf)buf.next;
		}
		return this;
	}

	@Override
	public void release() {
		DirectByteOutputBuf buf = this.first;
		while(buf!=null){
			buf.release();
			buf = (DirectByteOutputBuf)buf.next;
		}

	}

	@Override
	public InputBuf input() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public long size() {
		assert first.alloc.executor().inLoop() ;
		return size;
	}

}
