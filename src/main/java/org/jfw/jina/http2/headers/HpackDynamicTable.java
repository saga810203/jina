package org.jfw.jina.http2.headers;

public class HpackDynamicTable {
	// a circular queue of header fields
	HpackHeaderField[] hpackHeaderFields;
	int head;
	int tail;
	private long size;
	private long capacity = -1; // ensure setCapacity creates the array

	/**
	 * Creates a new dynamic table with the specified initial capacity.
	 */
	public HpackDynamicTable(long initialCapacity) {
		setCapacity(initialCapacity);
	}

	/**
	 * Return the number of header fields in the dynamic table.
	 */
	public int length() {
		int length;
		if (head < tail) {
			length = hpackHeaderFields.length - tail + head;
		} else {
			length = head - tail;
		}
		return length;
	}

	/**
	 * Return the current size of the dynamic table. This is the sum of the size
	 * of the entries.
	 */
	public long size() {
		return size;
	}

	/**
	 * Return the maximum allowable size of the dynamic table.
	 */
	public long capacity() {
		return capacity;
	}

	/**
	 * Return the header field at the given index. The first and newest entry is
	 * always at index 1, and the oldest entry is at the index length().
	 */
	public HpackHeaderField getEntry(int index) {
		if (index <= 0 || index > length()) {
			throw new IndexOutOfBoundsException();
		}
		int i = head - index;
		if (i < 0) {
			return hpackHeaderFields[i + hpackHeaderFields.length];
		} else {
			return hpackHeaderFields[i];
		}
	}

	/**
	 * Add the header field to the dynamic table. Entries are evicted from the
	 * dynamic table until the size of the table and the new header field is
	 * less than or equal to the table's capacity. If the size of the new entry
	 * is larger than the table's capacity, the dynamic table will be cleared.
	 */
	public void add(HpackHeaderField header) {
		int headerSize = header.size();
		if (headerSize > capacity) {
			clear();
			return;
		}
		while (capacity - size < headerSize) {
			remove();
		}
		hpackHeaderFields[head++] = header;
		size += header.size();
		if (head == hpackHeaderFields.length) {
			head = 0;
		}
	}

	/**
	 * Remove and return the oldest header field from the dynamic table.
	 */
	public HpackHeaderField remove() {
		HpackHeaderField removed = hpackHeaderFields[tail];
		if (removed == null) {
			return null;
		}
		size -= removed.size();
		hpackHeaderFields[tail++] = null;
		if (tail == hpackHeaderFields.length) {
			tail = 0;
		}
		return removed;
	}

	/**
	 * Remove all entries from the dynamic table.
	 */
	public void clear() {
		while (tail != head) {
			hpackHeaderFields[tail++] = null;
			if (tail == hpackHeaderFields.length) {
				tail = 0;
			}
		}
		head = 0;
		tail = 0;
		size = 0;
	}

	/**
	 * Set the maximum size of the dynamic table. Entries are evicted from the
	 * dynamic table until the size of the table is less than or equal to the
	 * maximum size.
	 */
	public void setCapacity(long capacity) {
		if (capacity < 0 || capacity > 4294967295L /* [0xFFFFFFFFL] */) {
			throw new IllegalArgumentException("capacity is invalid: " + capacity);
		}
		// initially capacity will be -1 so init won't return here
		if (this.capacity == capacity) {
			return;
		}
		this.capacity = capacity;

		if (capacity == 0) {
			clear();
		} else {
			// initially size will be 0 so remove won't be called
			while (size > capacity) {
				remove();
			}
		}

		int maxEntries = (int) (capacity / HpackHeaderField.HEADER_ENTRY_OVERHEAD);
		if (capacity % HpackHeaderField.HEADER_ENTRY_OVERHEAD != 0) {
			maxEntries++;
		}

		// check if capacity change requires us to reallocate the array
		if (hpackHeaderFields != null && hpackHeaderFields.length == maxEntries) {
			return;
		}

		HpackHeaderField[] tmp = new HpackHeaderField[maxEntries];

		// initially length will be 0 so there will be no copy
		int len = length();
		int cursor = tail;
		for (int i = 0; i < len; i++) {
			HpackHeaderField entry = hpackHeaderFields[cursor++];
			tmp[i] = entry;
			if (cursor == hpackHeaderFields.length) {
				cursor = 0;
			}
		}

		tail = 0;
		head = tail + len;
		hpackHeaderFields = tmp;
	}
}
