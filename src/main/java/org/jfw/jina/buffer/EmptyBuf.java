package org.jfw.jina.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;

public final class EmptyBuf implements InputBuf {

	public final static InputBuf INSTANCE = new EmptyBuf();

	private EmptyBuf() {
	}

	@Override
	public BufAllocator alloc() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readableBytes() {
		return 0;
	}

	@Override
	public boolean readable() {
		return false;
	}

	@Override
	public boolean readable(int size) {
		return false;
	}

	@Override
	public boolean readBoolean() {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte readByte() {
		throw new UnsupportedOperationException();
	}

	@Override
	public short readUnsignedByte() {
		throw new UnsupportedOperationException();
	}

	@Override
	public short readShort() {
		throw new UnsupportedOperationException();
	}

	@Override
	public short readShortLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readUnsignedShort() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readUnsignedShortLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readMedium() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readMediumLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readUnsignedMedium() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readUnsignedMediumLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readInt() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readIntLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long readUnsignedInt() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long readUnsignedIntLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long readLong() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long readLongLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public char readChar() {
		throw new UnsupportedOperationException();
	}

	@Override
	public float readFloat() {
		throw new UnsupportedOperationException();
	}

	@Override
	public float readFloatLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double readDouble() {
		throw new UnsupportedOperationException();
	}

	@Override
	public double readDoubleLE() {
		throw new UnsupportedOperationException();
	}

	@Override
	public InputBuf readBytes(byte[] dst) {
		throw new UnsupportedOperationException();
	}

	@Override
	public InputBuf readBytes(byte[] dst, int dstIndex, int length) {
		throw new UnsupportedOperationException();
	}

	@Override
	public InputStream readAsInputStream(int length) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readBytes(GatheringByteChannel out) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int readBytes(FileChannel out, long position) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public InputBuf skipBytes(int length) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int indexOf(byte value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void release() {
	}

	@Override
	public InputBuf duplicate() {
		return this;
	}

	@Override
	public InputBuf slice() {
		return this;
	}

	@Override
	public InputBuf retain() {
		return this;
	}

}
