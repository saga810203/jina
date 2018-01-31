package org.jfw.jina.buffer;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.Relier;

public interface BufAllocator {
	    OutputBuf buffer();
	    AsyncExecutor executor();
        void support(Relier<byte[]> relier);	    
}
