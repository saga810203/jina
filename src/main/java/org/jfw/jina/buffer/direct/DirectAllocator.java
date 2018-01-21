package org.jfw.jina.buffer.direct;

import java.nio.ByteBuffer;

import org.jfw.jina.buffer.BufAllocator;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.Relier;
import org.jfw.jina.core.impl.AbstractAsyncExecutor;

public class DirectAllocator implements BufAllocator {
	private final AbstractAsyncExecutor executor;
	DirectOutputBuf head;
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
	public DirectOutputBuf buffer() {
		assert executor.inLoop();
		if(head==null){
			return new DirectOutputBuf(this, ByteBuffer.allocateDirect(capacity));
		}else{
			DirectOutputBuf ret = head;
			head =(DirectOutputBuf)ret.next;
			ret.next = null;
			--this.numOfIdleBuf;
			return ret.retain();
		}
	}
	
	void release(DirectOutputBuf buf){
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
	public void support(Relier<byte[]> relier) {
		relier.use(this.swap);
	}
}
