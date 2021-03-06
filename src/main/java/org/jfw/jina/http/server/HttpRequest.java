package org.jfw.jina.http.server;

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
	void suspendRead();
	void resumeRead(); 
	void setRequestExecutor(RequestExecutor builder);
	void abort();
	public interface RequestExecutor{
		void setAsyncExecutor(HttpAsyncExecutor executor);
		void requestBody(HttpRequest request,byte[] buffer,int index,int length,boolean end);
		void execute(HttpRequest request,HttpResponse response);
		void error(HttpRequest request,int code);
	}
}
