package org.jfw.jina.http2;

import org.jfw.jina.http.HttpHeaders;


public interface FrameWriter {
	/**
	 * 
	 * @param streamId
	 * @param responseStatus
	 * @param headers
	 * @param endofStream
	 * @return false( > MAX_HEADER_LIST_SIZE)
	 */
	boolean writeHeaders(int streamId, int responseStatus, HttpHeaders headers, boolean endofStream);
	/**
	 * 
	 * @param streamId
	 * @param headers
	 * @param endOfStream
	 * @return false( > MAX_HEADER_LIST_SIZE)
	 */
	boolean writeHeaders(int streamId,HttpHeaders headers,boolean endOfStream);

	void writePriority(int streamId, int streamDependency, short weight, boolean exclusive);

	void writeRstStream(int streamId, long errorCode);

	void writeSettings(Http2Settings setting);
	void writeSettingAck();
	void writePing(byte[] buffer);
	void writePingAck(byte[] buffer);
	void writeGoAway(int lastStreamId, long errorCode, byte[] buffer,int index,int length);
	void writeWindowUpdate(int streamId,int windowSizeIncrement);
	void recvSettingAck();
	void recvPingAck(byte[] buffer);
	void windowUpdate(int size);
	
	
    boolean isAppendToHeaderTable(String name,String value);
}
