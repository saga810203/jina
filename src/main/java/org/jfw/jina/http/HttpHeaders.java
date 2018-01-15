package org.jfw.jina.http;

import java.util.Map;

public  interface HttpHeaders {
	String get(String name);
	Map<String,String> elements();
}
