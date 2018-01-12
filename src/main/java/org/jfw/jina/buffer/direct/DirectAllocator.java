package org.jfw.jina.buffer.direct;

import java.nio.ByteBuffer;

import org.jfw.jina.buffer.BufAllocator;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor;

public class DirectAllocator implements BufAllocator {
	private final AbstractAsyncExecutor executor;
	DirectByteOutputBuf head;
	private int numOfIdleBuf = 0;
	private final int maxIdleBuf;
	private final int capacity;
	private byte[]  swap;
	public DirectAllocator(AbstractAsyncExecutor executor,int capacity,int maxIdleBuf) {
		assert executor !=null && executor.inLoop();
		this.executor= executor;
		this.capacity =Math.max(capacity,4192);
		this.swap = new byte[this.capacity];
		this.maxIdleBuf =Math.max(maxIdleBuf,1024*16);
	}

	@Override
	public DirectByteOutputBuf buffer() {
		assert executor.inLoop();
		if(head==null){
			return new DirectByteOutputBuf(this, ByteBuffer.allocateDirect(capacity));
		}else{
			DirectByteOutputBuf ret = head;
			head =(DirectByteOutputBuf)ret.next;
			ret.next = null;
			--this.numOfIdleBuf;
			return ret.retain();
		}
	}
	
	void release(DirectByteOutputBuf buf){
		if(this.numOfIdleBuf< this.maxIdleBuf){
			++this.numOfIdleBuf;
			buf.next = head;
			this.head = buf;
		}else{
			buf.next = null;			
		}
	}



	@Override
	public AsyncExecutor executor() {
		return this.executor;
	}



	@Override
	public OutputBuf compositeBuffer() {
		return new CompositeOutputBuf(this.buffer());
	}


	@Override
	public byte[] swap() {
		assert executor.inLoop() && null!= this.swap;
		byte[] ret = this.swap;
		this.swap =null;
		return ret;
	}

	@Override
	public void swap(byte[] buf) {
		assert executor.inLoop() && null!=buf && null== this.swap;
		this.swap = buf;		
	}

}
