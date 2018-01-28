package org.jfw.jina.http2.headers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.util.StringUtil;

public class HpackStaticTable {
	private static final List<HpackHeaderField> STATIC_TABLE = Arrays.asList(/* 1 */ newEmptyHeaderField(HttpConsts.H2_AUTHORITY),
			/* 2 */ newHeaderField(HttpConsts.H2_METHOD, HttpConsts.GET), /* 3 */ newHeaderField(HttpConsts.H2_METHOD, HttpConsts.POST),
			/* 4 */ newHeaderField(HttpConsts.H2_PATH, "/"), /* 5 */ newHeaderField(HttpConsts.H2_PATH, "/index.html"),
			/* 6 */ newHeaderField(HttpConsts.H2_SCHEME, HttpConsts.H2_HTTP), /* 7 */ newHeaderField(HttpConsts.H2_SCHEME, HttpConsts.H2_HTTP),
			/* 8 */ newHeaderField(HttpConsts.H2_STATUS, "200"), /* 9 */ newHeaderField(HttpConsts.H2_STATUS, "204"),
			/* 10 */ newHeaderField(HttpConsts.H2_STATUS, "206"), /* 11 */ newHeaderField(HttpConsts.H2_STATUS, "304"),
			/* 12 */ newHeaderField(HttpConsts.H2_STATUS, "400"), /* 13 */ newHeaderField(HttpConsts.H2_STATUS, "404"),
			/* 14 */ newHeaderField(HttpConsts.H2_STATUS, "500"), /* 15 */ newEmptyHeaderField(HttpConsts.ACCEPT_CHARSET), // "accept-charset"),
			/* 16 */ newHeaderField(HttpConsts.ACCEPT_ENCODING, "gzip, deflate"), /* 17 */ newEmptyHeaderField(HttpConsts.ACCEPT_LANGUAGE),
			/* 18 */ newEmptyHeaderField(HttpConsts.ACCEPT_RANGES), /* 19 */ newEmptyHeaderField(HttpConsts.ACCEPT),
			/* 20 */ newEmptyHeaderField(HttpConsts.ACCESS_CONTROL_ALLOW_ORIGIN), /* 21 */ newEmptyHeaderField(HttpConsts.AGE),
			/* 22 */ newEmptyHeaderField(HttpConsts.ALLOW), /* 23 */ newEmptyHeaderField(HttpConsts.AUTHORIZATION), // "authorization"),
			/* 24 */ newEmptyHeaderField(HttpConsts.CACHE_CONTROL), // "cache-control"),
			/* 25 */ newEmptyHeaderField(HttpConsts.CONTENT_DISPOSITION), // "content-disposition"),
			/* 26 */ newEmptyHeaderField(HttpConsts.CONTENT_ENCODING), // "content-encoding"),
			/* 27 */ newEmptyHeaderField(HttpConsts.CONTENT_LANGUAGE), // "content-language"),
			/* 28 */ newEmptyHeaderField(HttpConsts.CONTENT_LENGTH), // "content-length"),
			/* 29 */ newEmptyHeaderField(HttpConsts.CONTENT_LOCATION), // "content-location"),
			/* 30 */ newEmptyHeaderField(HttpConsts.CONTENT_RANGE), // "content-range"),
			/* 31 */ newEmptyHeaderField(HttpConsts.CONTENT_TYPE), // "content-type"),
			/* 32 */ newEmptyHeaderField(HttpConsts.COOKIE), // "cookie"),
			/* 33 */ newEmptyHeaderField(HttpConsts.DATE), // "date"),
			/* 34 */ newEmptyHeaderField(HttpConsts.ETAG), // "etag"),
			/* 35 */ newEmptyHeaderField(HttpConsts.EXPECT), // "expect"),
			/* 36 */ newEmptyHeaderField(HttpConsts.EXPIRES), // "expires"),
			/* 37 */ newEmptyHeaderField(HttpConsts.FROM), // "from"),
			/* 38 */ newEmptyHeaderField(HttpConsts.HOST), // "host"),
			/* 39 */ newEmptyHeaderField(HttpConsts.IF_MATCH), // "if-match"),
			/* 40 */ newEmptyHeaderField(HttpConsts.IF_MODIFIED_SINCE), // "if-modified-since"),
			/* 41 */ newEmptyHeaderField(HttpConsts.IF_NONE_MATCH), // "if-none-match"),
			/* 42 */ newEmptyHeaderField(HttpConsts.IF_RANGE), // "if-range"),
			/* 43 */ newEmptyHeaderField(HttpConsts.IF_UNMODIFIED_SINCE), // "if-unmodified-since"),
			/* 44 */ newEmptyHeaderField(HttpConsts.LAST_MODIFIED), // "last-modified"),
			/* 45 */ newEmptyHeaderField(HttpConsts.LINK), // "link"),
			/* 46 */ newEmptyHeaderField(HttpConsts.LOCATION), // "location"),
			/* 47 */ newEmptyHeaderField(HttpConsts.MAX_FORWARDS), // "max-forwards"),
			/* 48 */ newEmptyHeaderField(HttpConsts.PROXY_AUTHENTICATE), // "proxy-authenticate"),
			/* 49 */ newEmptyHeaderField(HttpConsts.PROXY_AUTHORIZATION), // "proxy-authorization"),
			/* 50 */ newEmptyHeaderField(HttpConsts.RANGE), // "range"),
			/* 51 */ newEmptyHeaderField(HttpConsts.REFERER), // "referer"),
			/* 52 */ newEmptyHeaderField("refresh"), /* 53 */ newEmptyHeaderField("retry-after"), /* 54 */ newEmptyHeaderField(HttpConsts.SERVER), // "server"),
			/* 55 */ newEmptyHeaderField(HttpConsts.SET_COOKIE), // "set-cookie"),
			/* 56 */ newEmptyHeaderField("strict-transport-security"), /* 57 */ newEmptyHeaderField(HttpConsts.TRANSFER_ENCODING), // "transfer-encoding"),
			/* 58 */ newEmptyHeaderField(HttpConsts.USER_AGENT), // "user-agent"),
			/* 59 */ newEmptyHeaderField(HttpConsts.VARY), // "vary"),
			/* 60 */ newEmptyHeaderField(HttpConsts.VIA), // "via"),
			/* 61 */ newEmptyHeaderField(HttpConsts.WWW_AUTHENTICATE) // "www-authenticate")
	);

	private static HpackHeaderField newEmptyHeaderField(String name) {
		return new HpackHeaderField(name, StringUtil.EMPTY_STRING);
	}

	private static HpackHeaderField newHeaderField(String name, String value) {
		return new HpackHeaderField(name, value);
	}

	private static final Map<String, Integer> STATIC_INDEX_BY_NAME = createMap();

	/**
	 * The number of header fields in the static table.
	 */
	public static final int length = STATIC_TABLE.size();

	/**
	 * Return the header field at the given index value.
	 */
	public static HpackHeaderField getEntry(int index) {
		return STATIC_TABLE.get(index - 1);
	}

	/**
	 * Returns the lowest index value for the given header field name in the
	 * static table. Returns -1 if the header field name is not in the static
	 * table.
	 */
	public static int getIndex(CharSequence name) {
		Integer index = STATIC_INDEX_BY_NAME.get(name);
		if (index == null) {
			return -1;
		}
		return index;
	}

	public static int getIndex(String name, String value) {
		int index = getIndex(name);
		if (index == -1) {
			return -1;
		}

		// Note this assumes all entries for a given header field are
		// sequential.
		while (index <= length) {
			HpackHeaderField entry = getEntry(index);
			if (entry.value.equals(value))
				return index;
			index++;
		}

		return -1;
	}

	private static Map<String, Integer> createMap() {
		int length = STATIC_TABLE.size();
		Map<String, Integer> ret = new HashMap<String, Integer>(61);
		for (int index = length; index > 0; index--) {
			HpackHeaderField entry = getEntry(index);
			String name = entry.name;
			ret.put(name, index);
		}
		return ret;
	}

	private HpackStaticTable() {
	}
}
