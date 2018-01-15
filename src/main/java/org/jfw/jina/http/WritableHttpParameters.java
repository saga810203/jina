package org.jfw.jina.http;

public interface WritableHttpParameters extends HttpParameters{
	WritableHttpParameters addParameter(String name,String value);
	WritableHttpParameters addUTF8Parameter(String name,String value) ;
	WritableHttpParameters remove(String name);
	WritableHttpParameters remove(String name,String value);	
}
