package org.jfw.jina.http.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.util.concurrent.AsyncTask;

public interface HttpResponse {
	void addHeader(String name,String value);
	void setStatus(int sc);
	void setStatus(int sc,String scStr);
	void addBody(InputBuf buf);
	void flush(AsyncTask task);
	void fail();
}
