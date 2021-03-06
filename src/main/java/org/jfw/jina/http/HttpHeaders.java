package org.jfw.jina.http;

import java.util.List;
import java.util.Map;

public  interface HttpHeaders extends Iterable<Map.Entry<String, String>> {
	String get(String name);
	List<String> getList(String name);
	java.util.Iterator<Map.Entry<String,String>> iterator();
}
