package org.jfw.jina.http.server;

import java.util.Map;

import org.jfw.jina.buffer.InputBuf;

public interface HttpRequest {
	enum HttpMethod {
		GET , POST , PUT , DELETE
	}
	
	HttpMethod method();
	String path();
	String queryString();
	String hash();
	String getHeader(String name);
	String getParameter(String name);
	String[] getParameters(String name);	
	
	HttpResponse response();
	void setBodyBuilder(HttpRequestBodyBuilder builder);
	public interface HttpRequestBodyBuilder{
		Map<String,String[]> getParameterMap() throws Exception;
		void begin() ;
		void handleBody(InputBuf buf);
		void end();
	}
}
