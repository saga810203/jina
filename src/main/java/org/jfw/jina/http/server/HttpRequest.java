package org.jfw.jina.http.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.http.HttpHeaders;

public interface HttpRequest {
	enum HttpMethod {
		GET , POST , PUT , DELETE
	}
	HttpMethod method();
	String path();
	String queryString();
	String hash();
	HttpHeaders headers();
	void setRequestExecutor(RequestExecutor builder);
	public interface RequestExecutor{
		void setAsyncExecutor(HttpAsyncExecutor executor);
		void requestBody(HttpRequest request,InputBuf buf);
		void execute(HttpRequest request,HttpResponse response);
		void error(HttpRequest request,int code);
	}
}
