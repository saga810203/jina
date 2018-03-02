package org.jfw.jina.http2;

import org.jfw.jina.http2.impl.Http2FrameReader;

public class Http2Settings {
	public static final long MAX_HEADER_TABLE_SIZE = 0xffffffffL;
	public static final long MAX_CONCURRENT_STREAMS = 0xffffffffL;
	public static final long MAX_INITIAL_WINDOW_SIZE = Integer.MAX_VALUE;
	public static final long MAX_FRAME_SIZE_LOWER_BOUND = 0x4000;
	public static final long MAX_FRAME_SIZE_UPPER_BOUND = 0xffffff;
	public static final long MAX_HEADER_LIST_SIZE = 0xffffffffL;
	public static final long MIN_HEADER_TABLE_SIZE = 0;
	public static final long MIN_CONCURRENT_STREAMS = 0;
	public static final long MIN_INITIAL_WINDOW_SIZE = 0;
	public static final long MIN_HEADER_LIST_SIZE = 0;

	public static final int DEFAULT_WINDOW_SIZE = 65535;
	public static final short DEFAULT_PRIORITY_WEIGHT = 16;
	public static final int DEFAULT_HEADER_TABLE_SIZE = 4096;

	public static final long DEFAULT_HEADER_LIST_SIZE = 8192;
	public static final long DEFAULT_MAX_FRAME_SIZE = MAX_FRAME_SIZE_LOWER_BOUND;
	public static final long SMALLEST_MAX_CONCURRENT_STREAMS = 100;
	static final long DEFAULT_MAX_RESERVED_STREAMS = SMALLEST_MAX_CONCURRENT_STREAMS;
	static final long DEFAULT_MIN_ALLOCATION_CHUNK = 1024;
	static final long DEFAULT_INITIAL_HUFFMAN_DECODE_CAPACITY = 32;

	public static final char SETTINGS_HEADER_TABLE_SIZE = 1;
	public static final char SETTINGS_ENABLE_PUSH = 2;
	public static final char SETTINGS_MAX_CONCURRENT_STREAMS = 3;
	public static final char SETTINGS_INITIAL_WINDOW_SIZE = 4;
	public static final char SETTINGS_MAX_FRAME_SIZE = 5;
	public static final char SETTINGS_MAX_HEADER_LIST_SIZE = 6;

	private long[] config = new long[] { -1, -1, -1, -1, -1, -1, -1 };

	/**
	 * Gets the {@code SETTINGS_HEADER_TABLE_SIZE} value. If unavailable,
	 * returns {@code null}.
	 */
	public Long headerTableSize() {
		long ret = config[SETTINGS_HEADER_TABLE_SIZE];
		return ret < 0 ? null : ret;
	}

	/**
	 * Sets the {@code SETTINGS_HEADER_TABLE_SIZE} value.
	 *
	 * @throws IllegalArgumentException
	 *             if verification of the setting fails.
	 */
	public Http2Settings headerTableSize(long value) {
		if (value < MIN_HEADER_TABLE_SIZE || value > MAX_HEADER_TABLE_SIZE) {
			throw new IllegalArgumentException("Setting HEADER_TABLE_SIZE is invalid: " + value);
		}
		config[SETTINGS_HEADER_TABLE_SIZE] = value;
		return this;
	}

	/**
	 * Gets the {@code SETTINGS_ENABLE_PUSH} value. If unavailable, returns
	 * {@code null}.
	 */
	public Boolean pushEnabled() {
		long value = config[SETTINGS_ENABLE_PUSH];
		if (value < 0) {
			return null;
		}
		return value == 1;
	}

	/**
	 * Sets the {@code SETTINGS_ENABLE_PUSH} value.
	 */
	public Http2Settings pushEnabled(boolean enabled) {
		config[SETTINGS_ENABLE_PUSH] = (enabled ? 1 : 0);
		return this;
	}

	/**
	 * Gets the {@code SETTINGS_MAX_CONCURRENT_STREAMS} value. If unavailable,
	 * returns {@code null}.
	 */
	public Long maxConcurrentStreams() {
		long ret = config[SETTINGS_MAX_CONCURRENT_STREAMS];
		return ret < 0 ? null : ret;

	}

	/**
	 * Sets the {@code SETTINGS_MAX_CONCURRENT_STREAMS} value.
	 *
	 * @throws IllegalArgumentException
	 *             if verification of the setting fails.
	 */
	public Http2Settings maxConcurrentStreams(long value) {
		if (value < MIN_CONCURRENT_STREAMS || value > MAX_CONCURRENT_STREAMS) {
			throw new IllegalArgumentException("Setting MAX_CONCURRENT_STREAMS is invalid: " + value);
		}

		config[SETTINGS_MAX_CONCURRENT_STREAMS] = value;
		return this;
	}

	/**
	 * Gets the {@code SETTINGS_INITIAL_WINDOW_SIZE} value. If unavailable,
	 * returns {@code null}.
	 */
	public Integer initialWindowSize() {
		long ret = config[SETTINGS_INITIAL_WINDOW_SIZE];
		return ret < 0 ? null : ((int) ret);
	}

	/**
	 * Sets the {@code SETTINGS_INITIAL_WINDOW_SIZE} value.
	 *
	 * @throws IllegalArgumentException
	 *             if verification of the setting fails.
	 */
	public Http2Settings initialWindowSize(long value) {
		if (value < MIN_INITIAL_WINDOW_SIZE || value > MAX_INITIAL_WINDOW_SIZE) {
			throw new IllegalArgumentException("Setting INITIAL_WINDOW_SIZE is invalid: " + value);
		}
		config[SETTINGS_INITIAL_WINDOW_SIZE] = value;
		return this;
	}

	/**
	 * Gets the {@code SETTINGS_MAX_FRAME_SIZE} value. If unavailable, returns
	 * {@code null}.
	 */
	public Integer maxFrameSize() {

		long ret = config[SETTINGS_MAX_FRAME_SIZE];
		return ret < 0 ? null : ((int) ret);

	}

	/**
	 * Sets the {@code SETTINGS_MAX_FRAME_SIZE} value.
	 *
	 * @throws IllegalArgumentException
	 *             if verification of the setting fails.
	 */
	public Http2Settings maxFrameSize(long value) {
		if (!isMaxFrameSizeValid(value)) {
			throw new IllegalArgumentException("Setting MAX_FRAME_SIZE is invalid: " + value);
		}
		config[SETTINGS_MAX_FRAME_SIZE] = value;
		return this;
	}

	/**
	 * Gets the {@code SETTINGS_MAX_HEADER_LIST_SIZE} value. If unavailable,
	 * returns {@code null}.
	 */
	public Long maxHeaderListSize() {
		long ret = config[SETTINGS_MAX_HEADER_LIST_SIZE];
		return ret < 0 ? null : (ret);
	}

	/**
	 * Sets the {@code SETTINGS_MAX_HEADER_LIST_SIZE} value.
	 *
	 * @throws IllegalArgumentException
	 *             if verification of the setting fails.
	 */
	public Http2Settings maxHeaderListSize(long value) {
		if (value < MIN_HEADER_LIST_SIZE || value > MAX_HEADER_LIST_SIZE) {
			throw new IllegalArgumentException("Setting MAX_HEADER_LIST_SIZE is invalid: " + value);
		}
		config[SETTINGS_MAX_HEADER_LIST_SIZE] = value;
		return this;
	}

	/**
	 * Clears and then copies the given settings into this object.
	 */
	public Http2Settings copyFrom(Http2Settings settings) {
		System.arraycopy(settings.config, 0, config, 0, config.length);
		return this;
	}

	public int size() {
		int ret = 0;
		for (int i = 1; i < 7; ++i) {
			if (config[i] >= 0) {
				++ret;
			}
		}
		return ret;
	}

	public int writeToFrameBuffer(byte[] dest, int idx) {
		assert dest != null;
		assert idx >= 0;
		assert dest.length - idx >= 45;
		int ob = idx;
		idx += 3;
		dest[idx++] = Http2FrameReader.FRAME_TYPE_SETTINGS;
		dest[idx++] = 0;
		dest[idx++] = 0;
		dest[idx++] = 0;
		dest[idx++] = 0;
		dest[idx++] = 0;
		for (int i = 1; i < 7; ++i) {
			if (config[i] >= 0) {
				dest[idx++] = 0;
				dest[idx++] = (byte) i;
				int v = (int) config[i];
				dest[idx++] = (byte) (v >>> 24);
				dest[idx++] = (byte) (v >>> 16);
				dest[idx++] = (byte) (v >>> 8);
				dest[idx++] = (byte) (v);
			}
		}
		int ret = idx - ob;
		dest[ob++] = 0;
		dest[ob++] = 0;
		dest[ob++] = (byte) (ret - 9);
		return ret;
	}

	protected String keyToString(char key) {
		switch (key) {
			case SETTINGS_HEADER_TABLE_SIZE:
				return "HEADER_TABLE_SIZE";
			case SETTINGS_ENABLE_PUSH:
				return "ENABLE_PUSH";
			case SETTINGS_MAX_CONCURRENT_STREAMS:
				return "MAX_CONCURRENT_STREAMS";
			case SETTINGS_INITIAL_WINDOW_SIZE:
				return "INITIAL_WINDOW_SIZE";
			case SETTINGS_MAX_FRAME_SIZE:
				return "MAX_FRAME_SIZE";
			case SETTINGS_MAX_HEADER_LIST_SIZE:
				return "MAX_HEADER_LIST_SIZE";
			default:
				// Unknown keys.
				return Character.toString(key);
		}
	}
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append("Http2Setting==>{");
		for(int i =1 ; i < 7; ++i){
			sb.append(this.keyToString((char)i)).append(":").append(this.config[i]).append(",");
		}
		sb.setCharAt(sb.length()-1, '}');
		return sb.toString();
	}

	public static boolean isMaxFrameSizeValid(long maxFrameSize) {
		return maxFrameSize >= MAX_FRAME_SIZE_LOWER_BOUND && maxFrameSize <= MAX_FRAME_SIZE_UPPER_BOUND;
	}

	public static Http2Settings defaultSettings() {
		return new Http2Settings().maxHeaderListSize(DEFAULT_HEADER_LIST_SIZE);
	}
}
