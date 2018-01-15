package org.jfw.jina.http.impl;

import java.util.Map;

import org.jfw.jina.http.WritableHttpHeaders;
import org.jfw.jina.util.common.LinkedMap;

public class DefaultHttpHeaders extends LinkedMap<String, String> implements WritableHttpHeaders {

	@Override
	public String get(String name) {
		assert name != null;
		return super.get(name);
	}

	@Override
	protected Entry<String, String> find(Object key) {
		Entry<String, String> entry = head;
		while (entry != null) {
			if (entry.getKey().equalsIgnoreCase((String) key))
				return entry;
			entry = entry.next();
		}
		return null;
	}

	@Override
	protected Entry<String, String> findWithPrev(Object key) {
		Entry<String, String> entry = head;
		foundPrev = null;
		while (entry != null) {
			if (entry.getKey().equalsIgnoreCase((String) key)) {
				return entry;
			}
			foundPrev = entry;
			entry = foundPrev.next();
		}
		return null;
	}

	@Override
	public Map<String, String> elements() {
		return this;
	}

	@Override
	public String setHeader(String name, String value) {
		assert name != null && name.length() > 0 && value != null && value.length() > 0;
		return put(name, value);
	}

	@Override
	public String removeHeader(String name) {
		return super.remove(name);
	}

}
