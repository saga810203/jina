package org.jfw.jina.http2;

public interface Http2Stream {
	public static final byte STREAM_STATE_IDEL = 0;
	public static final byte STREAM_STATE_OPEN = 0b1000;
	public static final byte STREAM_STATE_CLOSED = 0b100;
	public static final byte STREAM_STATE_CLOSED_LOCAL = 0b101;
	public static final byte STREAM_STATE_CLOSED_REMOTE = 0b110;
	void changeInitialWindwSize(int size);
}
