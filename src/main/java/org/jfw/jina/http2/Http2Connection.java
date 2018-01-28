package org.jfw.jina.http2;

import org.jfw.jina.http.HttpHeaders;

public interface Http2Connection {
	void recvHeaders(HttpHeaders headers,int streamDependency, short weight, boolean exclusive,boolean endOfStream);
	void recvHeaders(HttpHeaders headers,boolean endOfStream);
	void resetStream(long error);
    void applySetting(Http2Settings setting);
    void goAway(int lastStreamId,long errorCode);
    void streamWindowUpdate(int size);
    void handleStreamData(int size,boolean endOfStream);
    void handlePriority(int streamDependency, short weight,boolean exclusive);
}
