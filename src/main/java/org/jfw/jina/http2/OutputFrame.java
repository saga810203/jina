package org.jfw.jina.http2;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;

public interface OutputFrame {
	int writableBytes();

	boolean writeable(int size);

	int write(byte[] buffer, int index, int length);
	int write(InputBuf buf);

	byte type();

	OutputFrame type(byte type);

	int payload();

	byte flag();

	OutputFrame flag(byte flag);
	int streamId();
	OutputFrame streamId(int streamId);

	boolean flush(SocketChannel channel) throws IOException;

}
