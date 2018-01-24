package org.jfw.jina.http2;

import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpHeaders;


public interface FrameWriter {
	void writeResponseHeader(int streamId, int stauts, HttpHeaders headers, boolean endStream);

	void writePriority(int streamId, int streamDependency, short weight, boolean exclusive);

	void writeRstStream(int streamId, long errorCode);

	void writeSettings(Http2Settings setting);

	void writeSettingAck();

	void writePing(byte[] buffer);

	void writePingAck(byte[] buffer);

	void writeGoAway(int lastStreamId, long errorCode, byte[] buffer);

	void writeWindowUpdate(int streamId,int windowSizeIncrement);

	void writeData(DataFrame frame);

	DataFrame newDataFrame();

	public interface DataFrame {
		DataFrame streamId(int id);

		DataFrame flag(byte flag);

		DataFrame listenner(TaskCompletionHandler handler);

		int write(byte[] buffer, int index, int length);
	}
}
