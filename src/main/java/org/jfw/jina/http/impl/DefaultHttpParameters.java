package org.jfw.jina.http.impl;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.jfw.jina.http.WritableHttpParameters;
import org.jfw.jina.util.StringUtil;

public class DefaultHttpParameters implements WritableHttpParameters {

	private Entry head = null;
	private Entry foundPrev = null;

	protected Entry find(String name) {
		assert name != null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key))
				return entry;
			entry = entry.next;
		}
		return null;
	}

	protected Entry findWithFoundPrev(String name) {
		assert name != null;
		foundPrev = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key))
				return entry;
			foundPrev = entry;
			entry = foundPrev.next;
		}
		return null;
	}

	@Override
	public String get(String name) {
		Entry entry = this.find(name);
		return entry == null ? null : entry.value;
	}

	@Override
	public String getUTF8(String name) throws UnsupportedEncodingException {
		Entry entry = this.find(name);
		return entry == null ? null : StringUtil.urlUTF8Decoding(entry.value);
	}

	@Override
	public List<String> getList(String name) {
		LinkedList<String> ret = new LinkedList<String>();
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key))
				ret.add(entry.value);
			entry = entry.next;
		}
		return ret;
	}

	@Override
	public List<String> getUTF8List(String name, Charset charset) throws UnsupportedEncodingException {
		LinkedList<String> ret = new LinkedList<String>();
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key))
				ret.add(StringUtil.urlUTF8Decoding(entry.value));
			entry = entry.next;
		}
		return ret;
	}

	@Override
	public Iterator<java.util.Map.Entry<String, String>> elements() {
		return new Iterator<java.util.Map.Entry<String, String>>() {
			private Entry c = head;

			@Override
			public boolean hasNext() {
				return c != null;
			}

			@Override
			public java.util.Map.Entry<String, String> next() {
				Entry ret = c;
				c = ret.next;
				return ret;
			}
		};
	}

	@Override
	public WritableHttpParameters addParameter(String name, String value) {
		head = new Entry(name, value, head);
		return this;
	}

	@Override
	public WritableHttpParameters addUTF8Parameter(String name, String value) {
		try {
			head = new Entry(name, StringUtil.urlUTF8Encoding(value), head);
		} catch (UnsupportedEncodingException e) {
			// IGNORE
		}
		return this;
	}

	@Override
	public WritableHttpParameters remove(String name) {
		assert name != null;
		foundPrev = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key)) {
				if (foundPrev == null) {
					this.head = entry.next;
					entry = head;
				} else {
					foundPrev.next = entry.next;
					entry = foundPrev.next;
				}
			} else {
				foundPrev = entry;
				entry = foundPrev.next;
			}
		}
		return this;
	}

	@Override
	public WritableHttpParameters remove(String name, String value) {
		assert name != null && value!=null;
		foundPrev = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key)&& value.equals(entry.value)) {
				if (foundPrev == null) {
					this.head = entry.next;
					entry = head;
				} else {
					foundPrev.next = entry.next;
					entry = foundPrev.next;
				}
			} else {
				foundPrev = entry;
				entry = foundPrev.next;
			}
		}
		return this;
	}

	protected static class Entry implements java.util.Map.Entry<String, String> {
		private final String key;
		private String value;
		private Entry next;

		public Entry(String key, Entry next) {
			this.key = key;
			this.next = next;
		}

		public Entry(String key, String value, Entry next) {
			this.key = key;
			this.value = value;
			this.next = next;
		}

		public String getValue() {
			return value;
		}

		public String setValue(String value) {
			String old = this.value;
			this.value = value;
			return old;
		}

		public Entry next() {
			return next;
		}

		public void next(Entry next) {
			this.next = next;
		}

		public String getKey() {
			return key;
		}
	}

}
