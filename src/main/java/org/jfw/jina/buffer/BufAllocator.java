package org.jfw.jina.buffer;

import org.jfw.jina.util.concurrent.AsyncExecutor;

public interface BufAllocator {
	    OutputBuf buffer();
	    OutputBuf compositeBuffer();
	    AsyncExecutor executor();
	    byte[] swap();
	    void swap(byte[] buf);
}
