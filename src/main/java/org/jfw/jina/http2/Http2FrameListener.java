package org.jfw.jina.http2;

public interface Http2FrameListener {
	void onDataRead(int padding, boolean endOfStream);

	void onHeadersRead();

	void onHeadersRead(int streamDependency, short weight, boolean exclusive);

	void onPriorityRead(int streamDependency, short weight, boolean exclusive);

	void onRstStreamRead(int streamId, long errorCode);

	void onSettingsAckRead();

	void onSettingsRead();

	void onPingRead();

	void onPingAckRead();

	void onPushPromiseRead(int streamId, int promisedStreamId, int padding);

	void onGoAwayRead(int lastStreamId, long errorCode);

	void onWindowUpdateRead(int streamId, int windowSizeIncrement);

	void onUnknownFrame(byte frameType, int streamId);
}
