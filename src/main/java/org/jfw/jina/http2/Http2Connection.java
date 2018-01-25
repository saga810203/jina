package org.jfw.jina.http2;


public interface Http2Connection {
	void createStream(int streamDependency, short weight, boolean exclusive,boolean endOfStream);
	void createStream(boolean endOfStream);
	void resetStream(long error);
    void applySetting(Http2Settings setting);
    void goAway(int lastStreamId,long errorCode);
    void streamWindowUpdate(int size);
    void handleStreamData(int size,boolean endOfStream);
    void handlePriority(int streamDependency, short weight,boolean exclusive);
}
