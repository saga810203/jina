package org.jfw.jina.buffer;

import org.jfw.jina.util.concurrent.AsyncExecutor;

public interface ByteBufAllocator {
	    ByteBuf buffer();
	    CompositeByteBuf compositeBuffer();
	    AsyncExecutor executor();
	    byte[] swap(int size);
}
