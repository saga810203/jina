package org.jfw.jina.http2;

import org.jfw.jina.http2.Http2FrameChannel.Http2Setting;

public interface Http2Connection {
	
	Stream createStream(int streamDependency, short weight, boolean exclusive,boolean endOfStream);
	
	Stream createStream(boolean endOfStream);
	Stream openedStream(int streamId);
	Stream stream(int streamId);
	void resetStream(int streamId);
    void setting(Http2Setting setting);
    void goWay();
    void incWindowSize(int size);
    void handlePriority()
	
	
	public interface Stream{
		void incWindowSize(int num);
		void hanldData(boolean endOfStream);
	}
}
