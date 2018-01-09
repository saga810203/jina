package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import org.jfw.jina.buffer.ByteBuf;
import org.jfw.jina.util.CharsetUtil;
import org.jfw.jina.util.StringUtil;

class DirectByteBuf implements ByteBuf {
	private int refCnt;
	private final ByteBuffer buffer;
	private int readerIndex;
	private int writerIndex;
	private final DirectAllocator alloc;

	DirectByteBuf(DirectAllocator alloc, ByteBuffer buffer) {
		this.alloc = alloc;
		this.buffer = buffer;
		this.refCnt = 1;
	}

	@Override
	public int capacity() {
		assert alloc.executor().inLoop();
		return this.buffer.capacity();
	}

	@Override
	public int readerIndex() {
		assert alloc.executor().inLoop();
		return this.readerIndex;
	}

	@Override
	public ByteBuf readerIndex(int readerIndex) {
		assert readerIndex >= 0 && readerIndex <= this.writerIndex;
		this.readerIndex = readerIndex;
		return this;
	}

	@Override
	public int writerIndex() {
		assert alloc.executor().inLoop();
		return this.writerIndex;
	}

	@Override
	public ByteBuf writerIndex(int writerIndex) {
		assert alloc.executor().inLoop();
		assert writerIndex >= this.readerIndex && writerIndex <= this.capacity();
		this.writerIndex = writerIndex;
		return this;
	}

	@Override
	public ByteBuf setIndex(int readerIndex, int writerIndex) {
		assert alloc.executor().inLoop();
		assert readerIndex > 0 && writerIndex >= readerIndex && writerIndex < this.capacity();
		this.readerIndex = readerIndex;
		this.writerIndex = writerIndex;
		return this;
	}

	@Override
	public int readableBytes() {
		assert alloc.executor().inLoop();
		return this.writerIndex - this.readerIndex;
	}

	@Override
	public int writableBytes() {
		assert alloc.executor().inLoop();
		return this.capacity() - this.writerIndex;
	}

	@Override
	public boolean isReadable() {
		assert alloc.executor().inLoop();
		return this.writerIndex > this.readerIndex;
	}

	@Override
	public boolean isReadable(int size) {
		assert alloc.executor().inLoop();
		assert size > 0;
		return this.writerIndex - this.readerIndex >= size;
	}

	public boolean isReadable(int index, int size) {
		assert alloc.executor().inLoop();
		assert size > 0 && index >= 0;
		return this.writerIndex - index >= size;
	}

	@Override
	public boolean isWritable() {
		assert alloc.executor().inLoop();
		return this.capacity() > this.writerIndex;
	}

	@Override
	public boolean isWritable(int size) {
		assert alloc.executor().inLoop();
		assert size > 0;
		return this.capacity() - this.writerIndex >= size;
	}

	public boolean isWritable(int index, int size) {
		assert alloc.executor().inLoop();
		assert size > 0;
		assert index >= 0;
		return this.capacity() - index >= size;
	}

	@Override
	public ByteBuf clear() {
		assert alloc.executor().inLoop();
		this.writerIndex = this.readerIndex = 0;
		return this;
	}

	@Override
	public ByteBuf discardReadBytes() {
		assert alloc.executor().inLoop();
		assert refCnt == 1;
		if (readerIndex == 0) {
			return this;
		}
		int size = this.readableBytes();

		if (size > 0) {
			byte[] tmp = this.alloc.swap(size);
			this.getBytes(readerIndex, tmp, 0, size);
			this.setBytes(0, tmp, 0, size);
			this.readerIndex = 0;
			this.writerIndex = size;
		} else {
			this.buffer.clear();
			writerIndex = readerIndex = 0;
		}
		return this;
	}

	@Override
	public boolean getBoolean(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 1);
		return buffer.get(index) != 0;
	}

	@Override
	public boolean readBoolean() {
		assert alloc.executor().inLoop() && this.isReadable(1);
		return buffer.get(this.readerIndex++) != 0;
	}

	@Override
	public byte getByte(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 1);
		return this.buffer.get(index);
	}

	@Override
	public short getUnsignedByte(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 1);
		return (short) (buffer.get(index) & 0xFF);
	}

	@Override
	public byte readByte() {
		assert alloc.executor().inLoop() && this.isReadable(1);
		return buffer.get(this.readerIndex++);
	}

	@Override
	public short readUnsignedByte() {
		assert alloc.executor().inLoop() && this.isReadable(1);
		return (short) (buffer.get(this.readerIndex++) & 0xFF);
	}

	@Override
	public short getShort(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 2);
		return this.buffer.getShort(index);
	}

	@Override
	public short getShortLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 2);
		return Short.reverseBytes(this.buffer.getShort(index));
	}

	@Override
	public int getUnsignedShort(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 2);
		return this.buffer.getShort(index) & 0xFFFF;
	}

	@Override
	public int getUnsignedShortLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 2);
		return Short.reverseBytes(this.buffer.getShort(index)) & 0xFFFF;
	}

	@Override
	public short readShort() {
		assert alloc.executor().inLoop() && this.isReadable(2);
		short s = this.buffer.getShort(this.readerIndex);
		this.readerIndex += 2;
		return s;
	}

	@Override
	public short readShortLE() {
		assert alloc.executor().inLoop() && this.isReadable(2);
		short s = this.buffer.getShort(this.readerIndex);
		this.readerIndex += 2;
		return Short.reverseBytes(s);
	}

	@Override
	public int readUnsignedShort() {
		assert alloc.executor().inLoop() && this.isReadable(2);
		int s = this.buffer.getShort(this.readerIndex) & 0xFFFF;
		this.readerIndex += 2;
		return s;
	}

	@Override
	public int readUnsignedShortLE() {
		assert alloc.executor().inLoop() && this.isReadable(2);
		short s = this.buffer.getShort(this.readerIndex);
		this.readerIndex += 2;
		return Short.reverseBytes(s) & 0xFFFF;
	}

	@Override
	public int getMedium(int index) {
		int value = getUnsignedMedium(index);
		if ((value & 0x800000) != 0) {
			value |= 0xff000000;
		}
		return value;
	}

	@Override
	public int getMediumLE(int index) {
		int value = getUnsignedMediumLE(index);
		if ((value & 0x800000) != 0) {
			value |= 0xff000000;
		}
		return value;
	}

	@Override
	public int getUnsignedMedium(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 3);
		return (buffer.get(this.readerIndex) & 0xff) << 16 | (buffer.get(this.readerIndex + 1) & 0xff) << 8 | buffer.get(this.readerIndex + 2) & 0xff;
	}

	@Override
	public int getUnsignedMediumLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 3);
		return buffer.get(readerIndex) & 0xff | (buffer.get(readerIndex + 1) & 0xff) << 8 | (buffer.get(readerIndex + 2) & 0xff) << 16;
	}

	@Override
	public int readMedium() {
		int i = this.getMedium(this.readerIndex);
		this.readerIndex += 3;
		return i;
	}

	@Override
	public int readMediumLE() {
		int i = this.getMediumLE(this.readerIndex);
		this.readerIndex += 3;
		return i;
	}

	@Override
	public int readUnsignedMedium() {
		int i = this.getUnsignedMedium(this.readerIndex);
		this.readerIndex += 3;
		return i;
	}

	@Override
	public int readUnsignedMediumLE() {
		int i = this.getUnsignedMediumLE(this.readerIndex);
		this.readerIndex += 3;
		return i;
	}

	@Override
	public int getInt(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 4);
		return this.buffer.getInt(this.readerIndex);
	}

	@Override
	public int getIntLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 4);
		return Integer.reverseBytes(buffer.getInt(this.readerIndex));
	}

	@Override
	public long getUnsignedInt(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 4);
		return this.buffer.getInt(this.readerIndex) & 0xFFFFFFFFL;
	}

	@Override
	public long getUnsignedIntLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 4);
		return Integer.reverseBytes(buffer.getInt(this.readerIndex)) & 0xFFFFFFFFL;
	}

	@Override
	public int readInt() {
		assert alloc.executor().inLoop() && this.isReadable(4);
		int i = this.buffer.getInt(this.readerIndex);
		this.readerIndex += 4;
		return i;
	}

	@Override
	public int readIntLE() {
		assert alloc.executor().inLoop() && this.isReadable(4);
		int i = this.buffer.getInt(this.readerIndex);
		this.readerIndex += 4;
		return Integer.reverseBytes(i);
	}

	@Override
	public long readUnsignedInt() {
		assert alloc.executor().inLoop() && this.isReadable(4);
		int i = this.buffer.getInt(this.readerIndex);
		this.readerIndex += 4;
		return i & 0xFFFFFFFFL;
	}

	@Override
	public long readUnsignedIntLE() {
		assert alloc.executor().inLoop() && this.isReadable(4);
		int i = this.buffer.getInt(this.readerIndex);
		this.readerIndex += 4;
		return Integer.reverseBytes(i) & 0xFFFFFFFFL;
	}

	@Override
	public long getLong(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 8);
		return buffer.getLong(index);
	}

	@Override
	public long getLongLE(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 8);
		return Long.reverseBytes(buffer.getLong(index));
	}

	@Override
	public long readLong() {
		assert alloc.executor().inLoop() && this.isReadable(8);
		long l = buffer.getLong(this.readerIndex);
		this.readerIndex += 8;
		return l;
	}

	@Override
	public long readLongLE() {
		assert alloc.executor().inLoop() && this.isReadable(8);
		long l = buffer.getLong(this.readerIndex);
		this.readerIndex += 8;
		return Long.reverseBytes(l);
	}

	@Override
	public char getChar(int index) {
		assert alloc.executor().inLoop() && this.isReadable(index, 2);
		return (char) this.buffer.getShort(index);
	}

	@Override
	public char readChar() {
		assert alloc.executor().inLoop() && this.isReadable(2);
		short s = this.buffer.getShort(this.readerIndex);
		this.readerIndex += 2;
		return (char) s;
	}

	@Override
	public float getFloat(int index) {
		return Float.intBitsToFloat(getInt(index));
	}

	@Override
	public float getFloatLE(int index) {
		return Float.intBitsToFloat(getIntLE(index));
	}

	@Override
	public float readFloat() {
		float f = Float.intBitsToFloat(getInt(this.readerIndex));
		this.readerIndex += 4;
		return f;
	}

	@Override
	public float readFloatLE() {
		float f = Float.intBitsToFloat(getIntLE(this.readerIndex));
		this.readerIndex += 4;
		return f;
	}

	@Override
	public double getDouble(int index) {
		return Double.longBitsToDouble(getLong(index));
	}

	@Override
	public double getDoubleLE(int index) {
		return Double.longBitsToDouble(getLongLE(index));
	}

	@Override
	public double readDouble() {
		double d = Double.longBitsToDouble(getLong(this.readerIndex));
		this.readerIndex += 8;
		return d;
	}

	@Override
	public double readDoubleLE() {
		double d = Double.longBitsToDouble(getLongLE(this.readerIndex));
		this.readerIndex += 8;
		return d;
	}

	@Override
	public ByteBuf getBytes(int index, byte[] dst) {
		assert alloc.executor().inLoop() && this.isReadable(index, dst.length);
		this.buffer.mark();
		this.buffer.position(index);
		this.buffer.get(dst, 0, dst.length);
		this.buffer.reset();
		return this;
	}

	@Override
	public ByteBuf readBytes(byte[] dst) {
		assert alloc.executor().inLoop() && this.isReadable(dst.length);
		this.buffer.mark();
		this.buffer.position(this.readerIndex);
		this.buffer.get(dst, 0, dst.length);
		this.buffer.reset();
		this.readerIndex += dst.length;
		return this;
	}

	@Override
	public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
		assert alloc.executor().inLoop() && dstIndex >= 0 && length > 0 && ((dstIndex + length) <= dst.length) && this.isReadable(index, length);
		this.buffer.mark();
		this.buffer.position(index);
		this.buffer.get(dst, dstIndex, length);
		this.buffer.reset();
		return this;
	}

	@Override
	public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
		assert alloc.executor().inLoop() && dstIndex >= 0 && length > 0 && ((dstIndex + length) <= dst.length) && this.isReadable(length);
		this.buffer.mark();
		this.buffer.position(this.readerIndex);
		this.buffer.get(dst, dstIndex, length);
		this.buffer.reset();
		this.readerIndex += length;
		return this;
	}

	private static final Object CHAR_BUFFERS = new Object();

	private CharBuffer getCharBuffer() {
		CharBuffer buf = alloc.executor().getObject(CHAR_BUFFERS);
		if (buf == null) {
			buf = CharBuffer.allocate(1024 * 16);
			alloc.executor().setObject(CHAR_BUFFERS, buf);
		}
		return buf;
	}

	private static void decodeString(CharsetDecoder decoder, ByteBuffer src, CharBuffer dst) {
		try {
			CoderResult cr = decoder.decode(src, dst, true);
			if (!cr.isUnderflow()) {
				cr.throwException();
			}
			cr = decoder.flush(dst);
			if (!cr.isUnderflow()) {
				cr.throwException();
			}
		} catch (CharacterCodingException x) {
			throw new IllegalStateException(x);
		}
	}

	@Override
	public CharSequence getCharSequence(int index, int len, Charset charset) {
		assert alloc.executor().inLoop() && len >= 0 && this.isReadable(index, len);

		if (len == 0) {
			return StringUtil.EMPTY_STRING;
		}
		final CharsetDecoder decoder = CharsetUtil.decoder(charset);
		final int maxLength = (int) ((double) len * decoder.maxCharsPerByte());
		CharBuffer dst = getCharBuffer();
		if (dst.length() < maxLength) {
			dst = CharBuffer.allocate(maxLength);
		} else {
			dst.clear();
		}
		this.buffer.mark();
		int olimit = this.buffer.limit();
		try {
			this.buffer.position(index);
			this.buffer.limit(index + len);
			decodeString(decoder, this.buffer, dst);
		} finally {
			this.buffer.limit(olimit);
			this.buffer.reset();
		}
		return dst.flip().toString();
	}

	@Override
	public ByteBuf setBoolean(int index, boolean value) {
		assert isWritable(index, 1);
		this.buffer.put(index, (byte) (value ? 1 : 0));
		return this;
	}

	@Override
	public ByteBuf setByte(int index, int value) {
		assert isWritable(index, 1);
		this.buffer.put(index, (byte) value);
		return this;
	}

	@Override
	public ByteBuf setShort(int index, int value) {
		assert isWritable(index, 2);
		this.buffer.putShort(index, (short) value);
		return this;
	}

	@Override
	public ByteBuf setShortLE(int index, int value) {
		assert isWritable(index, 2);
		this.buffer.putShort(index, Short.reverseBytes((short) value));
		return this;
	}

	@Override
	public ByteBuf setMedium(int index, int value) {
		assert isWritable(index, 3);
		buffer.put(index, (byte) (value >>> 16));
		buffer.put(index + 1, (byte) (value >>> 8));
		buffer.put(index + 2, (byte) value);
		return this;
	}

	@Override
	public ByteBuf setMediumLE(int index, int value) {
		assert isWritable(index, 3);
		buffer.put(index, (byte) value);
		buffer.put(index + 1, (byte) (value >>> 8));
		buffer.put(index + 2, (byte) (value >>> 16));
		return this;
	}

	@Override
	public ByteBuf setInt(int index, int value) {
		assert isWritable(index, 4);
		buffer.putInt(index, value);
		return this;
	}

	@Override
	public ByteBuf setIntLE(int index, int value) {
		assert isWritable(index, 4);
		buffer.putInt(index, Integer.reverseBytes(value));
		return this;
	}

	@Override
	public ByteBuf setLong(int index, long value) {
		assert isWritable(index, 8);
		buffer.putLong(index, value);
		return this;
	}

	@Override
	public ByteBuf setLongLE(int index, long value) {
		assert isWritable(index, 8);
		buffer.putLong(index, Long.reverseBytes(value));
		return this;
	}

	@Override
	public ByteBuf setChar(int index, int value) {
		assert isWritable(index, 2);
		this.buffer.putShort(index, (short) value);
		return this;
	}

	@Override
	public ByteBuf setFloat(int index, float value) {
		return setInt(index, Float.floatToRawIntBits(value));
	}

	@Override
	public ByteBuf setFloatLE(int index, float value) {
		return setIntLE(index, Float.floatToRawIntBits(value));
	}

	@Override
	public ByteBuf setDouble(int index, double value) {
		return setLong(index, Double.doubleToRawLongBits(value));
	}

	@Override
	public ByteBuf setDoubleLE(int index, double value) {
		return setLongLE(index, Double.doubleToRawLongBits(value));
	}

	@Override
	public ByteBuf setBytes(int index, byte[] src) {
		assert src != null && src.length > 0 && isWritable(index, src.length);
		this.buffer.mark();
		try {
			buffer.position(index);
			buffer.put(src, 0, src.length);
		} finally {
			this.buffer.reset();
		}
		return this;
	}

	@Override
	public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
		assert src != null && srcIndex >= 0 && length > 0 && srcIndex + length <= src.length && isWritable(index, length);
		this.buffer.mark();
		try {
			buffer.position(index);
			buffer.put(src, srcIndex, length);
		} finally {
			this.buffer.reset();
		}
		return this;
	}

	@Override
	public int setCharSequence(int index, CharSequence sequence, Charset charset) {

		byte[] bs = sequence.toString().getBytes(charset);
		this.setBytes(index, bs);
		return bs.length;
	}

	@Override
	public InputStream readAsInputStream(int length) {
		assert isReadable(length);
		++this.refCnt;
		return new InputStream() {
			int idx = readerIndex;
			int limit = length;
			boolean realeased = false;

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				assert DirectByteBuf.this.alloc.executor().inLoop();
				assert b != null && off >= 0 && len > 0 && off + len <= b.length;
				assert !this.realeased;

				if (limit > 0) {
					int slen = Math.min(limit, len);
					getBytes(idx, b, off, slen);
					idx += slen;
					limit -= slen;
					return slen;
				}
				return -1;
			}

			@Override
			public long skip(long n) throws IOException {
				assert DirectByteBuf.this.alloc.executor().inLoop();
				assert n > 0;
				assert !this.realeased;
				if (limit > 0) {
					int slen = (int) Math.min(limit, n);
					idx += slen;
					limit -= slen;
					return slen;
				}
				return 0;
			}

			@Override
			public int available() throws IOException {
				assert DirectByteBuf.this.alloc.executor().inLoop();
				return limit;
			}

			@Override
			public void close() throws IOException {
				assert DirectByteBuf.this.alloc.executor().inLoop();
				if (!this.realeased) {
					this.realeased = true;
					--DirectByteBuf.this.refCnt;
				}
			}

			@Override
			public void mark(int readlimit) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void reset() throws IOException {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean markSupported() {
				return false;
			}

			@Override
			public int read() throws IOException {
				assert DirectByteBuf.this.alloc.executor().inLoop();
				assert !this.realeased;
				if (limit > 0) {
					byte i = getByte(this.idx);
					++this.idx;
					--limit;
					return i;
				}
				return -1;
			}
		};
	}

	@Override
	public int readBytes(GatheringByteChannel out) throws IOException {
		assert alloc.executor().inLoop();
		assert out != null && out.isOpen();
		int ret = 0;
		this.buffer.mark();
		buffer.position(this.readerIndex);
		buffer.limit(this.writerIndex);
		try {
			ret = out.write(buffer);
		} finally {
			this.buffer.clear();
		}
		this.readerIndex += ret;
		return ret;
	}

	@Override
	public CharSequence readCharSequence(int length, Charset charset) {
		CharSequence sequence = getCharSequence(readerIndex, length, charset);
		readerIndex += length;
		return sequence;
	}

	@Override
	public int readBytes(FileChannel out, long position) throws IOException {
		assert alloc.executor().inLoop();
		assert out != null && out.isOpen();
		int ret = 0;
		this.buffer.mark();
		buffer.position(this.readerIndex);
		buffer.limit(this.writerIndex);
		try {
			out.position(position);
			ret = out.write(buffer);
		} finally {
			this.buffer.clear();
		}
		this.readerIndex += ret;
		return ret;
	}

	@Override
	public ByteBuf skipBytes(int length) {
		assert isReadable(length);
		this.readerIndex+=length;
		return this;
	}

	@Override
	public ByteBuf writeBoolean(boolean value) {
		assert isWritable(1);
		this.buffer.put(this.writerIndex++, (byte) (value ? 1 : 0));
		return this;
	}

	@Override
	public ByteBuf writeByte(int value) {
		assert isWritable(1);
		this.buffer.put(this.writerIndex++, (byte) value );
		return this;
	}

	@Override
	public ByteBuf writeShort(int value) {
		assert isWritable(2);
		this.buffer.putShort(this.writerIndex, (short) value );
		this.writerIndex+=2;
		return this;
	}

	@Override
	public ByteBuf writeShortLE(int value) {
		assert isWritable(2);
		this.buffer.putShort(this.writerIndex, Short.reverseBytes((short) value));
		this.writerIndex+=2;
		return this;
	}

	@Override
	public ByteBuf writeMedium(int value) {
		assert isWritable( 3);
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) value);
		return this;
	}

	@Override
	public ByteBuf writeMediumLE(int value) {
		assert isWritable( 3);
		buffer.put(this.writerIndex++, (byte)value);
		buffer.put(this.writerIndex++, (byte) (value >>> 8));
		buffer.put(this.writerIndex++, (byte) (value >>> 16));
		return this;
	}

	@Override
	public ByteBuf writeInt(int value) {
		assert isWritable(4);
		buffer.putInt(this.writerIndex, value);
		this.writerIndex+=4;
		return this;
	}

	@Override
	public ByteBuf writeIntLE(int value) {
		assert isWritable(4);
		buffer.putInt(this.writerIndex, Integer.reverseBytes(value));
		this.writerIndex+=4;
		return this;
	}

	@Override
	public ByteBuf writeLong(long value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeLongLE(long value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeChar(int value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeFloat(float value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeFloatLE(float value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeDouble(double value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeDoubleLE(double value) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeBytes(ByteBuf src) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeBytes(ByteBuf src, int length) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeBytes(ByteBuf src, int srcIndex, int length) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeBytes(byte[] src) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ByteBuf writeBytes(byte[] src, int srcIndex, int length) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int writeBytes(ScatteringByteChannel in) throws IOException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int writeBytes(FileChannel in, long position, int length) throws IOException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ByteBuf writeZero(int length) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int writeCharSequence(CharSequence sequence, Charset charset) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int indexOf(int fromIndex, int toIndex, byte value) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int bytesBefore(byte value) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int bytesBefore(int length, byte value) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int bytesBefore(int index, int length, byte value) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ByteBuf retain() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void release() {
		// TODO Auto-generated method stub

	}

}
