package org.jfw.jina.util.concurrent;

import java.security.AccessController;
import java.security.PrivilegedAction;

public final class SystemPropertyUtil {
	public static boolean contains(String key) {
		return get(key) != null;
	}
	public static String get(String key) {
		return get(key, null);
	}
	public static String get(final String key, String def) {
		if (key == null) {
			throw new NullPointerException("key");
		}
		if (key.isEmpty()) {
			throw new IllegalArgumentException("key must not be empty.");
		}

		String value = null;
		try {
			if (System.getSecurityManager() == null) {
				value = System.getProperty(key);
			} else {
				value = AccessController.doPrivileged(new PrivilegedAction<String>() {
					public String run() {
						return System.getProperty(key);
					}
				});
			}
		} catch (SecurityException e) {
		}

		if (value == null) {
			return def;
		}

		return value;
	}
	public static boolean getBoolean(String key, boolean def) {
		String value = get(key);
		if (value == null) {
			return def;
		}

		value = value.trim().toLowerCase();
		if (value.isEmpty()) {
			return true;
		}

		if ("true".equals(value) || "yes".equals(value) || "1".equals(value)) {
			return true;
		}

		if ("false".equals(value) || "no".equals(value) || "0".equals(value)) {
			return false;
		}
		return def;
	}
	public static int getInt(String key, int def) {
		String value = get(key);
		if (value == null) {
			return def;
		}

		value = value.trim();
		try {
			return Integer.parseInt(value);
		} catch (Exception e) {
			// Ignore
		}
		return def;
	}
	public static long getLong(String key, long def) {
		String value = get(key);
		if (value == null) {
			return def;
		}

		value = value.trim();
		try {
			return Long.parseLong(value);
		} catch (Exception e) {
			// Ignore
		}
		return def;
	}

	private SystemPropertyUtil() {
	}
}
