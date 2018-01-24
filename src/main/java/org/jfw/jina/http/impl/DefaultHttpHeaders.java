package org.jfw.jina.http.impl;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.WritableHttpHeaders;

public class DefaultHttpHeaders implements WritableHttpHeaders {

	private Entry head = null;
	private Entry foundPrev = null;

	protected Entry find(String name) {
		assert name != null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key))
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
			if (name.equalsIgnoreCase(entry.key))
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
	public List<String> getList(String name) {
		LinkedList<String> ret = new LinkedList<String>();
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key))
				ret.add(entry.value);
			entry = entry.next;
		}
		return ret;
	}

	@Override
	public Iterator<java.util.Map.Entry<String, String>> iterator() {
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
	public DefaultHttpHeaders add(String name, String value) {
		head = new Entry(name, value, head);
		return this;
	}

	@Override
	public DefaultHttpHeaders remove(String name) {
		assert name != null;
		foundPrev = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key)) {
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
	public DefaultHttpHeaders remove(String name, String value) {
		assert name != null && value != null;
		foundPrev = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key) && value.equalsIgnoreCase(entry.value)) {
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

	@Override
	public DefaultHttpHeaders set(String name, String value) {
		assert name != null;
		foundPrev = null;
		Entry oe = null;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key)) {
				if (foundPrev == null) {
					this.head = entry.next;
					oe = entry;
					entry = head;
				} else {
					foundPrev.next = entry.next;
					oe = entry;
					entry = foundPrev.next;
				}
			} else {
				foundPrev = entry;
				entry = foundPrev.next;
			}
		}
		if (oe != null) {
			oe.setValue(value);
			oe.next(head);
			head = oe;
		} else {
			head = new Entry(name, value, head);
		}
		return this;
	}
	
	public boolean isKeepAlive(){
		Entry entry = this.head;
		while (entry != null) {
			if (HttpConsts.KEEP_ALIVE.equalsIgnoreCase(entry.key) && HttpConsts.CLOSE.equalsIgnoreCase(entry.value))
				return false;
			entry = entry.next;
		}
		return true;
	}

	@Override
	public DefaultHttpHeaders clear() {
		this.head = null;
		return this;
	}
	
	
	public boolean contains(String name,String value){
		assert null != name;
		assert null!= value;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equals(entry.key) && value.equals(entry.getValue()))
				return true;
			entry = entry.next;
		}
		return false;
	}
	public boolean containsIgnoreCase(String name,String value){
		assert null != name;
		assert null!= value;
		Entry entry = this.head;
		while (entry != null) {
			if (name.equalsIgnoreCase(entry.key) && value.equalsIgnoreCase(entry.getValue()))
				return true;
			entry = entry.next;
		}
		return false;
	}
}
