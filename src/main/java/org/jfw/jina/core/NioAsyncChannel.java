package org.jfw.jina.core;

import java.nio.channels.SelectionKey;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.util.Handler;

public interface NioAsyncChannel{
	void read();
	void write();
	void setSelectionKey(SelectionKey key);
	void close();
	void connected();
	public static final Handler RELEASE_INPUT_BUF = new Handler() {
		@Override
		public void process(Object obj) {
			((InputBuf) obj).release();
		}
	};
}
