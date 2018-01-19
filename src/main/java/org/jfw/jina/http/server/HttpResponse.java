package org.jfw.jina.http.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.http.HttpResponseStatus;

public interface HttpResponse {
	public static final int STATE_INIT = 5;
	public static final int STATE_SENDING_DATA = 15;
	public static final int STATE_SENDED = 20;
	
	int state();
	void addHeader(String name,String value);
	void setStatus(HttpResponseStatus httpResponseStatus);
	void addBody(InputBuf buf);
	void flush(InputBuf buf,AsyncTask task);
	void fail();
	void sendClientError(HttpResponseStatus error);
}
