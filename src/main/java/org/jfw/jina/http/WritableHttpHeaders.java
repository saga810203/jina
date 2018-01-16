package org.jfw.jina.http;

public interface WritableHttpHeaders extends HttpHeaders {
	WritableHttpHeaders set(String name,String value);
	WritableHttpHeaders add(String name,String value);
	WritableHttpHeaders remove(String name,String value);
	WritableHttpHeaders remove(String name);
	WritableHttpHeaders clear();
}
