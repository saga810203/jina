package org.jfw.jina.util.concurrent;

import java.nio.channels.SelectionKey;

public interface AsyncChannel{
	void read();
	void write();
	void setSelectionKey(SelectionKey key);
	void close();
	void connected();
}
