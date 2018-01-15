package org.jfw.jina.http;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public interface HttpParameters {
	String get(String name);
	String getUTF8(String name) throws UnsupportedEncodingException;
	List<String> getList(String name);
	List<String> getUTF8List(String name,Charset charset) throws UnsupportedEncodingException;
	Iterator<Map.Entry<String,String>> elements();
}
