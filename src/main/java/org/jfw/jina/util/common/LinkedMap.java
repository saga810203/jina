package org.jfw.jina.util.common;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class LinkedMap<K, V> implements Map<K, V> {
	private int size = 0;
	protected Entry<K, V> head = null;
	protected Entry<K, V> foundPrev = null;

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	protected Entry<K, V> find(Object key) {
		Entry<K, V> entry = head;
		while (entry != null) {
			if (entry.key.equals(key))
				return entry;
			entry = entry.next;
		}
		return null;
	}

	protected Entry<K, V> findWithPrev(Object key) {
		Entry<K, V> entry = head;
		foundPrev = null;
		while (entry != null) {
			if (entry.key.equals(key)) {
				return entry;
			}
			foundPrev = entry;
			entry = foundPrev.next;
		}
		return null;
	}

	protected Entry<K, V> findByValue(Object value) {
		Entry<K, V> entry = head;
		if (value == null) {
			while (entry != null) {
				if (entry.value == null)
					return entry;
				entry = entry.next;
			}
		} else {
			while (entry != null) {
				if (value.equals(entry.value))
					return entry;
				entry = entry.next;
			}
		}
		return null;
	}

	@Override
	public boolean containsKey(Object key) {
		return null != find(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return null != findByValue(value);
	}

	@Override
	public V get(Object key) {
		Entry<K, V> entry = this.find(key);
		return entry == null ? null : entry.value;
	}

	@Override
	public V put(K key, V value) {
		Entry<K, V> entry = this.find(key);
		if (entry == null) {
			this.head = new Entry<K, V>(key, value, head);
			++size;
			return null;
		} else {
			return entry.setValue(value);
		}
	}

	@Override
	public V remove(Object key) {
		Entry<K, V> entry = this.findWithPrev(key);
		if (entry == null) {
			return null;
		} else if (entry == head) {
			head = entry.next;
		} else {
			foundPrev.next = entry.next;
		}
		--size;
		return entry.value;
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for (java.util.Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
			K key = entry.getKey();
			if (key != null) {
				this.put(key, entry.getValue());
			}
		}
	}

	@Override
	public void clear() {
		this.head = null;
		this.size = 0;
	}

	@Override
	public Set<K> keySet() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<V> values() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		return entrySet;
	}

	private Set<java.util.Map.Entry<K, V>> entrySet = new Set<java.util.Map.Entry<K, V>>() {

		@Override
		public int size() {
			return size;
		}

		@Override
		public boolean isEmpty() {
			return size == 0;
		}

		@Override
		public boolean contains(Object o) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Iterator<java.util.Map.Entry<K, V>> iterator() {
			return new Iterator<java.util.Map.Entry<K, V>>() {
				Entry<K, V> curr = head;

				@Override
				public boolean hasNext() {
					return curr != null;
				}

				@Override
				public java.util.Map.Entry<K, V> next() {
					Entry<K, V> ret = curr;
					curr = ret.next;
					return ret;
				}
			};

		}

		@Override
		public Object[] toArray() {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T[] toArray(T[] a) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean add(java.util.Map.Entry<K, V> e) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean remove(Object o) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean containsAll(Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean addAll(Collection<? extends java.util.Map.Entry<K, V>> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void clear() {
			throw new UnsupportedOperationException();

		}

	};

	public static class Entry<K, V> implements java.util.Map.Entry<K, V> {
		protected final K key;
		protected V value;
		protected Entry<K, V> next;

		public Entry(K key, Entry<K, V> next) {
			assert null != key;
			this.key = key;
			this.next = next;
		}

		public Entry(K key, V value, Entry<K, V> next) {
			assert null != key;
			this.key = key;
			this.value = value;
			this.next = next;
		}

		@Override
		public K getKey() {
			return this.key;
		}

		@Override
		public V getValue() {
			return value;
		}

		@Override
		public V setValue(V value) {
			assert value != null;
			V old = this.value;
			this.value = value;
			return old;
		}

		public Entry<K, V> next() {
			return this.next;
		}
	}

}
