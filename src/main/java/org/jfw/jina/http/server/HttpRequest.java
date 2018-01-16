package org.jfw.jina.http.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpParameters;
import org.jfw.jina.http.WritableHttpParameters;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

public interface HttpRequest {
	enum HttpMethod {
		GET , POST , PUT , DELETE
	}
	NioAsyncExecutor executor();
	HttpMethod method();
	String path();
	String queryString();
	String hash();
	HttpHeaders headers();
	HttpParameters parameters();	
	void setBodyBuilder(RequestExecutor builder);
	public interface RequestExecutor{
		void setAsyncExecutor(HttpAsyncExecutor executor);
		void begin(HttpRequest request,WritableHttpParameters parameters) ;
		void appendRequestBody(HttpRequest request,InputBuf buf);
		void end(HttpRequest request,HttpResponse response,boolean validBody);
	}
}
