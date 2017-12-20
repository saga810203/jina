package org.jfw.jina.util.concurrent;

import java.nio.channels.SelectionKey;

public interface AsyncChannel {

	void read(AsyncExecutor executor);
	void write(AsyncExecutor executor);
	void setSelectionKey(SelectionKey key);
	void close(AsyncExecutor executor);
	void connected(AsyncExecutor executor);
}
