package org.jfw.jina.http2;

import org.jfw.jina.http2.Http2FrameChannel.Http2Setting;

public interface Http2Connection {
	
	Stream createStream(int streamId,boolean endOfStream);
	Stream opened(int streamId);
	Stream stream(int streamId);
	void resetStream(int streamId);
    void setting(Http2Setting setting);
    void goWay();
	
	
	public interface Stream{
		void incWindowUpdate(int num);
		void hanldData(boolean endOfStream);
	}
}
