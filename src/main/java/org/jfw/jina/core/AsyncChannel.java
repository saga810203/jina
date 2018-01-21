package org.jfw.jina.core;

import java.nio.channels.SelectionKey;

public interface AsyncChannel{
	void read();
	void write();
	void setSelectionKey(SelectionKey key);
	void close();
	void connected();
}
