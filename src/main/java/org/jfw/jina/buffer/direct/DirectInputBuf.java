package org.jfw.jina.buffer.direct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;

import org.jfw.jina.buffer.BufAllocator;
import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.InputBuf;

public class DirectInputBuf implements InputBuf {
	ByteBuffer buffer;
	protected int pos;
	protected final int limit;
	int refCnt;
	DirectByteOutputBuf wrap;

	DirectInputBuf(DirectByteOutputBuf wrap, int limit) {
		assert wrap != null && wrap.alloc.executor().inLoop();
		assert limit > 0;
		this.wrap = wrap;
		this.buffer = wrap.buffer();
		this.pos = 0;
		this.limit = limit;
		refCnt = 1;
	}

	@Override
	public int readableBytes() {
		assert wrap != null && wrap.alloc.executor().inLoop();
		return this.limit - this.pos;
	}

	@Override
	public boolean readable() {
		assert wrap != null && wrap.alloc.executor().inLoop();
		return this.limit > this.pos;
	}

	@Override
	public boolean readable(int size) {
		assert wrap != null && wrap.alloc.executor().inLoop();
		assert size > 0;
		return this.limit - this.pos >= size;
	}

	@Override
	public boolean readBoolean() {
		assert readable(1);
		return buffer.get(this.pos++) != 0;
	}

	@Override
	public byte readByte() {
		assert readable(1);
		return buffer.get(this.pos++);
	}

	@Override
	public short readUnsignedByte() {
		assert readable(1);
		return (short) (buffer.get(this.pos++) & 0xFF);
	}

	@Override
	public short readShort() {
		assert this.readable(2);
		short s = this.buffer.getShort(this.pos);
		this.pos += 2;
		return s;
	}

	@Override
	public short readShortLE() {
		assert this.readable(2);
		short s = this.buffer.getShort(this.pos);
		this.pos += 2;
		return Short.reverseBytes(s);
	}

	@Override
	public int readUnsignedShort() {
		assert readable(2);
		int s = this.buffer.getShort(this.pos) & 0xFFFF;
		this.pos += 2;
		return s;
	}

	@Override
	public int readUnsignedShortLE() {
		assert readable(2);
		short s = this.buffer.getShort(this.pos);
		this.pos += 2;
		return Short.reverseBytes(s) & 0xFFFF;
	}

	@Override
	public int readMedium() {
		int value = readUnsignedMedium();
		if ((value & 0x800000) != 0) {
			value |= 0xff000000;
		}
		return value;
	}

	@Override
	public int readMediumLE() {
		int value = readUnsignedMediumLE();
		if ((value & 0x800000) != 0) {
			value |= 0xff000000;
		}
		return value;
	}

	@Override
	public int readUnsignedMedium() {
		assert readable(3);
		return (buffer.get(pos++) & 0xff) << 16 | (buffer.get(pos++) & 0xff) << 8 | buffer.get(pos++) & 0xff;
	}

	@Override
	public int readUnsignedMediumLE() {
		assert readable(3);
		return buffer.get(pos++) & 0xff | (buffer.get(pos++) & 0xff) << 8 | (buffer.get(pos++) & 0xff) << 16;
	}

	@Override
	public int readInt() {
		assert readable(4);
		int i = this.buffer.getInt(this.pos);
		this.pos += 4;
		return i;
	}

	@Override
	public int readIntLE() {
		assert readable(4);
		int i = this.buffer.getInt(this.pos);
		this.pos += 4;
		return Integer.reverseBytes(i);
	}

	@Override
	public long readUnsignedInt() {
		assert readable(4);
		int i = this.buffer.getInt(this.pos);
		this.pos += 4;
		return i & 0xFFFFFFFFL;
	}

	@Override
	public long readUnsignedIntLE() {
		assert readable(4);
		int i = this.buffer.getInt(this.pos);
		this.pos += 4;
		return Integer.reverseBytes(i) & 0xFFFFFFFFL;
	}

	@Override
	public long readLong() {
		assert readable(8);
		long l = buffer.getLong(this.pos);
		this.pos += 8;
		return l;
	}

	@Override
	public long readLongLE() {
		assert readable(8);
		long l = buffer.getLong(this.pos);
		this.pos += 8;
		return Long.reverseBytes(l);
	}

	@Override
	public char readChar() {
		assert readable(2);
		short s = this.buffer.getShort(this.pos);
		this.pos += 2;
		return (char) s;
	}

	@Override
	public float readFloat() {
		return Float.intBitsToFloat(readInt());
	}

	@Override
	public float readFloatLE() {
		return Float.intBitsToFloat(readIntLE());
	}

	@Override
	public double readDouble() {
		return Double.longBitsToDouble(readLong());
	}

	@Override
	public double readDoubleLE() {
		return Double.longBitsToDouble(readLongLE());
	}

	@Override
	public InputBuf readBytes(byte[] dst) {
		assert dst != null;
		this.readBytes(dst, 0, dst.length);
		return this;
	}

	@Override
	public InputBuf readBytes(byte[] dst, int dstIndex, int length) {
		assert dst != null && dstIndex >= 0 && length > 0 && ((dstIndex + length) <= dst.length) && this.readable(length);
		this.buffer.mark();
		this.buffer.position(this.pos);
		try {
			this.buffer.get(dst, dstIndex, length);
		} finally {
			this.buffer.reset();
		}
		this.pos += length;
		return this;
	}

	@Override
	public InputStream readAsInputStream(int length) {
		assert readable(length);
		wrap.retain();
		return new InputStream() {
			int p = pos;
			int l = length;
			boolean realeased = false;

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				assert DirectInputBuf.this.wrap.alloc.executor().inLoop();
				assert b != null && off >= 0 && len > 0 && off + len <= b.length;
				assert !this.realeased;

				if (l > 0) {
					int slen = Math.min(l, len);
					buffer.mark();
					try {
						buffer.position(p);
						buffer.get(b, off, slen);
					} finally {
						buffer.reset();
					}
					p += slen;
					l -= slen;
					return slen;
				}
				return -1;
			}

			@Override
			public long skip(long n) throws IOException {
				assert DirectInputBuf.this.wrap.alloc.executor().inLoop();
				assert n > 0;
				assert !this.realeased;
				if (l > 0) {
					int slen = (int) Math.min(l, n);
					p += slen;
					l -= slen;
					return slen;
				}
				return 0;
			}

			@Override
			public int available() throws IOException {
				assert DirectInputBuf.this.wrap.alloc.executor().inLoop();
				return l;
			}

			@Override
			public void close() throws IOException {
				assert DirectInputBuf.this.wrap.alloc.executor().inLoop();
				if (!this.realeased) {
					this.realeased = true;
					DirectInputBuf.this.wrap.release();
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
				assert DirectInputBuf.this.wrap.alloc.executor().inLoop();
				assert !this.realeased;
				if (l > 0) {
					byte i = buffer.get(p++);
					--l;
					return i;
				}
				return -1;
			}
		};
	}

	@Override
	public int readBytes(GatheringByteChannel out) throws IOException {
		assert wrap != null && wrap.alloc.executor().inLoop();
		assert out != null && out.isOpen();
		int ret = 0;
		this.buffer.mark();
		buffer.position(this.pos);
		buffer.limit(this.limit);
		try {
			ret = out.write(buffer);
		} finally {
			this.buffer.clear();
		}
		this.pos += ret;
		return ret;
	}

	@Override
	public int readBytes(FileChannel out, long position) throws IOException {
		assert wrap != null && wrap.alloc.executor().inLoop();
		assert out != null && out.isOpen();
		int ret = 0;
		this.buffer.mark();
		buffer.position(this.pos);
		buffer.limit(this.limit);
		try {
			out.position(position);
			ret = out.write(buffer);
		} finally {
			this.buffer.clear();
		}
		this.pos += ret;
		return ret;
	}

	@Override
	public DirectInputBuf skipBytes(int length) {
		assert readable(length);
		this.pos += length;
		return this;
	}

	@Override
	public void release() {
		assert wrap != null && wrap.alloc.executor().inLoop();
		--refCnt;
		if(refCnt==0){
			wrap.release();
			this.buffer = null;
			this.wrap = null;
		}
	}

	@Override
	public int indexOf(byte value) {
		assert wrap != null && wrap.alloc.executor().inLoop();
		for (int i = this.pos; i < this.limit; ++i) {
			if (this.buffer.get(i) == value)
				return i - this.pos;
		}
		return -1;
	}

	@Override
	public InputBuf duplicate() {
		assert wrap != null && wrap.alloc.executor().inLoop();
		++wrap.refCnt;
		return new DirectInputBuf(wrap, limit);
	}

	@Override
	public SliceInputBuf slice() {
		assert this.readable();
		++wrap.refCnt;
		return new SliceInputBuf(wrap, this.pos, this.limit);
	}

	@Override
	public BufAllocator alloc() {
		assert this.wrap!= null;
		return this.wrap.alloc;
	}

	@Override
	public DirectInputBuf retain() {
		assert this.wrap!= null &&  this.wrap.alloc.executor().inLoop()&& refCnt >0;
        ++this.refCnt;
		return this;
	}

	@Override
	public boolean skipControlCharacters() {
		boolean skiped = false;
		final int wIdx =this.limit;
		int rIdx = pos;
		while (wIdx > rIdx) {
			int c = (int) (buffer.get(rIdx++) & 0xFF);
			if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
				rIdx--;
				skiped = true;
				break;
			}
		}
		pos = rIdx;
		return skiped;
	}

	@Override
	public int forEachByte(ByteProcessor processor) {
		for(int i =pos;i<this.limit;++i){
			if(!processor.process(this.buffer.get(i))){
				return i-pos;
			}
		}
		return -1;
	}

	@Override
	public InputBuf duplicate(int length) {
		assert this.limit-length >= this.pos;
		++wrap.refCnt;
		return new SliceInputBuf(wrap,pos, this.limit-length);
	}
}
