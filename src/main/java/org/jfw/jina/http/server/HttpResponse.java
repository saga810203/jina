package org.jfw.jina.http.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpResponseStatus;

public interface HttpResponse {
	public static final int STATE_INIT = 5;
	public static final int STATE_SENDING_DATA = 15;
	public static final int STATE_SENDED = 20;
	
	int state();
	void addHeader(String name,String value);
	void setStatus(HttpResponseStatus httpResponseStatus);
	void write(byte[] buffer,int index,int length);
	void flush(byte[] buffer,int index,int length,TaskCompletionHandler task);
	void flush(TaskCompletionHandler task);
	void flush();
	void flush(byte[] buffer,int index,int length);
	
	void unsafeContentLength(long length);
	void unsafeWrite(byte[] buffer,int index,int length);
    void unsafeWirte(InputBuf buffer,TaskCompletionHandler task);
    void unsafeFlush(InputBuf buffer,TaskCompletionHandler task);
    void unsafeFlush(InputBuf buffer);
    void unsafeFlush(TaskCompletionHandler task);
    void unsafeFlush();

	void fail();
	void sendClientError(HttpResponseStatus error);
}
