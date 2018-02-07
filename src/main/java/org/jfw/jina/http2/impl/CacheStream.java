package org.jfw.jina.http2.impl;

import java.io.OutputStream;

public class CacheStream extends OutputStream {
	private static final int INITAL_SIZE = 0x4000;
	private static final int ADD_STEP = 0x1000;

	byte[] buffer;
	int length;
	int widx;
	int ridx;

	public CacheStream() {
		this.buffer = new byte[INITAL_SIZE];
		this.length = INITAL_SIZE;
		this.widx = 0;
		this.ridx = 0;
	}

	public void reset(int size) {
		if(size>INITAL_SIZE){
			int nsize=INITAL_SIZE+4096;
			while(nsize<size){
				nsize+=ADD_STEP;
			}
			size= nsize;
		}else{
			size = INITAL_SIZE;
		}
		if(size > length){
			buffer = new byte[size];
			length = size;
		}else if(length != INITAL_SIZE){
			if(size>INITAL_SIZE){
				buffer = new byte[size];
				length = size;
			}else{
				this.buffer = new byte[INITAL_SIZE];
				this.length = INITAL_SIZE;
			}
		}
		this.widx = 0;
		this.ridx = 0;
	}

	void ensureCapacity(int size) {
		if (length < size) {
			int ol = length;
			while (ol < size) {
				ol += 4096;
			}
			byte[] nb = new byte[ol];
			System.arraycopy(buffer, 0, nb, 0, widx);
			buffer = nb;
		}
	}

	@Override
	public void write(byte[] b, int off, int len)  {
//		ensureCapacity(widx + len);
		System.arraycopy(b, off, buffer, widx, len);
		widx += len;
	}

	@Override
	public void write(int b) {
//		ensureCapacity(widx + 1);
		buffer[widx++] = (byte) b;
	}
}
