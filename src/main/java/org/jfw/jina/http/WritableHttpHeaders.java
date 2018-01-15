package org.jfw.jina.http;

public interface WritableHttpHeaders extends HttpHeaders {
	String setHeader(String name,String value);
	String removeHeader(String name);
}
