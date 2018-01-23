package org.jfw.jina.http2;

public interface Http2FrameListener {
	void onDataRead( boolean endOfStream);

	void onHeadersRead(boolean endOfStream);

	void onHeadersRead(int streamDependency, short weight, boolean exclusive,boolean endOfStream);

	void onPriorityRead(int streamDependency, short weight, boolean exclusive);

	void onRstStreamRead(long errorCode);

	void onSettingsAckRead();

	void onSettingsRead(Http2Settings settings);


	void onPushPromiseRead(int promisedStreamId);

	void onGoAwayRead(int lastStreamId, long errorCode);

	void onWindowUpdateRead(int windowSizeIncrement);

	void onUnknownFrame(byte frameType);
}
