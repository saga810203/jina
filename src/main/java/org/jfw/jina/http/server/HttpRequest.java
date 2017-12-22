package org.jfw.jina.http.server;

public interface HttpRequest {
	enum HttpMethod{
		GET,POST,PUT,DELETE
	}
	
	HttpMethod method();
	String uri();
	String query();
	String hash();
	String header(String name);
	String getParameger(String name);
	String[] getParamegers(String name);
}
