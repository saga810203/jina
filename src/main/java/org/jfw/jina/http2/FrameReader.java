package org.jfw.jina.http2;

import org.jfw.jina.buffer.InputBuf;

public interface FrameReader {
	void readFrame(InputBuf buf);
	//payload length =  size+padding;
	void handleData(int size, int padding,boolean endOfStream);
	void handleHeaders(boolean endOfStream);
	void handleHeaders(int streamDependency, short weight, boolean exclusive,boolean endOfStream);
	void handlePriority(int streamDependency, short weight, boolean exclusive);
	void handleRstStreamRead(long errorCode);
	void handleSettingsAckRead();
	void handleSettingsRead(Http2Settings settings);
	void onPushPromiseRead(int promisedStreamId);
	void handleGoAwayRead(int lastStreamId, long errorCode);
	void handleWindowUpdateRead(int windowSizeIncrement);
	void handleUnknownFrame(byte frameType);
}
