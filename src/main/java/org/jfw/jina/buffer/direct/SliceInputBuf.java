package org.jfw.jina.buffer.direct;

public class SliceInputBuf extends DirectInputBuf {
	private final int begin;
	SliceInputBuf(DirectOutputBuf wrap, int begin, int end) {
		super(wrap, end);
		assert begin > 0 && begin < end;
		this.begin = this.pos = begin;
	}

	@Override
	public SliceInputBuf duplicate() {
		assert wrap != null && wrap instanceof DirectOutputBuf && ((DirectOutputBuf) wrap).alloc.executor().inLoop();
		++wrap.refCnt;
		return new SliceInputBuf(wrap, begin, limit);
	}

	@Override
	public SliceInputBuf slice() {
		assert wrap != null && wrap instanceof DirectOutputBuf && ((DirectOutputBuf) wrap).alloc.executor().inLoop() && this.pos < this.limit;
		++wrap.refCnt;
		return new SliceInputBuf(wrap, pos, limit);
	}

}