package org.jfw.jina.util;

import java.io.IOException;
import java.io.OutputStream;

public class CacheStream extends OutputStream {
	
	private byte[] buffer;
	private int length;
	private int widx;
	private int ridx;
	
	public  CacheStream() {
		this.buffer = new byte[0x4000];
		this.length = 0x4000;
		this.widx = 0 ; 
		this.ridx = 0;
	}
	public void reset(){
		this.buffer = new byte[0x4000];
		this.length = 0x4000;
		this.widx = 0 ; 
		this.ridx = 0;
	}

	private void ensureCapacity(int size){
		while
		
	}
	
	@Override
	public void write(byte[] b) throws IOException {
		if(b.length+widx> length)
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		// TODO Auto-generated method stub
		super.write(b, off, len);
	}

	@Override
	public void write(int b) throws IOException {
		// TODO Auto-generated method stub
		
	}

}
