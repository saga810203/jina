package org.jfw.jina.buffer;

import org.jfw.jina.core.Relier;
import org.jfw.jina.util.concurrent.AsyncExecutor;

public interface BufAllocator {
	    OutputBuf buffer();
	    OutputBuf compositeBuffer();
	    AsyncExecutor executor();
        void support(Relier<byte[]> relier);	    
}
