package org.jfw.jina.core.impl;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;

final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

	SelectionKey[] keys;
	int size;

	SelectedSelectionKeySet() {
		keys = new SelectionKey[1024];
	}

	@Override
	public boolean add(SelectionKey o) {
		if (o == null) {
			return false;
		}

		keys[size++] = o;
		if (size == keys.length) {
			increaseCapacity();
		}

		return true;
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public boolean remove(Object o) {
		return false;
	}

	@Override
	public boolean contains(Object o) {
		return false;
	}

	@Override
	public Iterator<SelectionKey> iterator() {
		throw new UnsupportedOperationException();
	}

	void reset() {
		reset(0);
	}

	void reset(int start) {
		Arrays.fill(keys, start, size, null);
		size = 0;
	}

	private void increaseCapacity() {
		SelectionKey[] newKeys = new SelectionKey[keys.length << 1];
		System.arraycopy(keys, 0, newKeys, 0, size);
		keys = newKeys;
	}
}