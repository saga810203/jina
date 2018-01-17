package org.jfw.jina.http;

import org.jfw.jina.util.StringUtil;

public class HttpResponseStatus {
	private final int code;
	private final byte[] reason;
	private final boolean keepAlive;
	private final byte[] defautContent;

	private HttpResponseStatus(int code, String reason, boolean keepAlive, byte[] defaultContent) {
		assert code > 99 && code < 600 && reason != null && reason.length() > 0;
		this.code = code;
		this.reason = StringUtil.utf8(reason);
		this.keepAlive = keepAlive;
		this.defautContent = defaultContent;
	}

	private HttpResponseStatus(int code, byte[] reason, boolean keepAlive, byte[] defaultContent) {
		assert code > 99 && code < 600 && reason != null && reason.length > 0;
		this.code = code;
		this.reason = reason;
		this.keepAlive = keepAlive;
		this.defautContent = defaultContent;
	}

	private HttpResponseStatus(int code, String reason, boolean keepAlive) {
		assert code > 99 && code < 600 && reason != null && reason.length() > 0;
		this.code = code;
		this.reason = StringUtil.utf8(reason);
		this.keepAlive = true;
		if(code>399 && code < 500){
			this.defautContent = StringUtil.utf8("<html><head><title>"+code+" "+reason+"</title></head><body bgcolor=\"white\"><center><h1>"+code +" "+reason +"</h1></center><hr><center>jina async server</center></body></html>");
		}else {
			this.defautContent = null;
		}
	}

	private HttpResponseStatus(int code, String reason) {
		assert code > 99 && code < 600 && reason != null && reason.length() > 0;
		this.code = code;
		this.reason = StringUtil.utf8(reason);
		if(code>399 && code < 500){
			this.keepAlive = false;
			this.defautContent = StringUtil.utf8("<html><head><title>"+code+" "+reason+"</title></head><body bgcolor=\"white\"><center><h1>"+code +" "+reason +"</h1></center><hr><center>jina async server</center></body></html>");
		}else {
			this.keepAlive = true;
			this.defautContent = null;
		}
	}
	public int getCode() {
		return code;
	}

	public byte[] getReason() {
		return reason;
	}

	public boolean isKeepAlive() {
		return keepAlive;
	}

	public byte[] getDefautContent() {
		return defautContent;
	}

	public static HttpResponseStatus build(int code, byte[] reason, boolean keepAlive) {
		if (code < 100 || code > 599)
			throw new IllegalArgumentException("error http response status code");
		if (reason == null || reason.length == 0)
			throw new IllegalArgumentException("error http response status reason");
		return new HttpResponseStatus(code, reason, keepAlive, null);
	}

	public static final HttpResponseStatus CONTINUE = new HttpResponseStatus(100, "Continue");

	public static final HttpResponseStatus SWITCHING_PROTOCOLS = new HttpResponseStatus(101, "Switching Protocols");
	public static final HttpResponseStatus PROCESSING = new HttpResponseStatus(102, "Processing");
	public static final HttpResponseStatus OK = new HttpResponseStatus(200, "OK");
	public static final HttpResponseStatus CREATED = new HttpResponseStatus(201, "Created");
	public static final HttpResponseStatus ACCEPTED = new HttpResponseStatus(202, "Accepted");

	public static final HttpResponseStatus NON_AUTHORITATIVE_INFORMATION = new HttpResponseStatus(203, "Non-Authoritative Information");
	public static final HttpResponseStatus NO_CONTENT = new HttpResponseStatus(204, "No Content");
	public static final HttpResponseStatus RESET_CONTENT = new HttpResponseStatus(205, "Reset Content");
	public static final HttpResponseStatus PARTIAL_CONTENT = new HttpResponseStatus(206, "Partial Content");

	/**
	 * 207 Multi-Status (WebDAV, RFC2518)
	 */
	public static final HttpResponseStatus MULTI_STATUS = new HttpResponseStatus(207, "Multi-Status");

	/**
	 * 300 Multiple Choices
	 */
	public static final HttpResponseStatus MULTIPLE_CHOICES = new HttpResponseStatus(300, "Multiple Choices");

	/**
	 * 301 Moved Permanently
	 */
	public static final HttpResponseStatus MOVED_PERMANENTLY = new HttpResponseStatus(301, "Moved Permanently");

	/**
	 * 302 Found
	 */
	public static final HttpResponseStatus FOUND = new HttpResponseStatus(302, "Found");

	/**
	 * 303 See Other (since HTTP/1.1)
	 */
	public static final HttpResponseStatus SEE_OTHER = new HttpResponseStatus(303, "See Other");

	/**
	 * 304 Not Modified
	 */
	public static final HttpResponseStatus NOT_MODIFIED = new HttpResponseStatus(304, "Not Modified");

	/**
	 * 305 Use Proxy (since HTTP/1.1)
	 */
	public static final HttpResponseStatus USE_PROXY = new HttpResponseStatus(305, "Use Proxy");

	/**
	 * 307 Temporary Redirect (since HTTP/1.1)
	 */
	public static final HttpResponseStatus TEMPORARY_REDIRECT = new HttpResponseStatus(307, "Temporary Redirect");

	/**
	 * 308 Permanent Redirect (RFC7538)
	 */
	public static final HttpResponseStatus PERMANENT_REDIRECT = new HttpResponseStatus(308, "Permanent Redirect");

	/**
	 * 400 Bad Request
	 */
	public static final HttpResponseStatus BAD_REQUEST = new HttpResponseStatus(400, "Bad Request",false);

	/**
	 * 401 Unauthorized
	 */
	public static final HttpResponseStatus UNAUTHORIZED = new HttpResponseStatus(401, "Unauthorized");

	/**
	 * 402 Payment Required
	 */
	public static final HttpResponseStatus PAYMENT_REQUIRED = new HttpResponseStatus(402, "Payment Required");

	/**
	 * 403 Forbidden
	 */
	public static final HttpResponseStatus FORBIDDEN = new HttpResponseStatus(403, "Forbidden");

	/**
	 * 404 Not Found
	 */
	public static final HttpResponseStatus NOT_FOUND = new HttpResponseStatus(404, "Not Found");

	/**
	 * 405 Method Not Allowed
	 */
	public static final HttpResponseStatus METHOD_NOT_ALLOWED = new HttpResponseStatus(405, "Method Not Allowed",false);

	/**
	 * 406 Not Acceptable
	 */
	public static final HttpResponseStatus NOT_ACCEPTABLE = new HttpResponseStatus(406, "Not Acceptable");

	/**
	 * 407 Proxy Authentication Required
	 */
	public static final HttpResponseStatus PROXY_AUTHENTICATION_REQUIRED = new HttpResponseStatus(407, "Proxy Authentication Required");

	/**
	 * 408 Request Timeout
	 */
	public static final HttpResponseStatus REQUEST_TIMEOUT = new HttpResponseStatus(408, "Request Timeout");

	/**
	 * 409 Conflict
	 */
	public static final HttpResponseStatus CONFLICT = new HttpResponseStatus(409, "Conflict");

	/**
	 * 410 Gone
	 */
	public static final HttpResponseStatus GONE = new HttpResponseStatus(410, "Gone");

	/**
	 * 411 Length Required
	 */
	public static final HttpResponseStatus LENGTH_REQUIRED = new HttpResponseStatus(411, "Length Required",false);

	/**
	 * 412 Precondition Failed
	 */
	public static final HttpResponseStatus PRECONDITION_FAILED = new HttpResponseStatus(412, "Precondition Failed");

	/**
	 * 413 Request Entity Too Large
	 */
	public static final HttpResponseStatus REQUEST_ENTITY_TOO_LARGE = new HttpResponseStatus(413, "Request Entity Too Large");

	/**
	 * 414 Request-URI Too Long
	 */
	public static final HttpResponseStatus REQUEST_URI_TOO_LONG = new HttpResponseStatus(414, "Request-URI Too Long", false);

	/**
	 * 415 Unsupported Media Type
	 */
	public static final HttpResponseStatus UNSUPPORTED_MEDIA_TYPE = new HttpResponseStatus(415, "Unsupported Media Type");

	/**
	 * 416 Requested Range Not Satisfiable
	 */
	public static final HttpResponseStatus REQUESTED_RANGE_NOT_SATISFIABLE = new HttpResponseStatus(416, "Requested Range Not Satisfiable");

	/**
	 * 417 Expectation Failed
	 */
	public static final HttpResponseStatus EXPECTATION_FAILED = new HttpResponseStatus(417, "Expectation Failed");

	/**
	 * 421 Misdirected Request
	 *
	 * <a href=
	 * "https://tools.ietf.org/html/draft-ietf-httpbis-http2-15#section-9.1.2">
	 * 421 Status Code</a>
	 */
	public static final HttpResponseStatus MISDIRECTED_REQUEST = new HttpResponseStatus(421, "Misdirected Request");

	/**
	 * 422 Unprocessable Entity (WebDAV, RFC4918)
	 */
	public static final HttpResponseStatus UNPROCESSABLE_ENTITY = new HttpResponseStatus(422, "Unprocessable Entity");

	/**
	 * 423 Locked (WebDAV, RFC4918)
	 */
	public static final HttpResponseStatus LOCKED = new HttpResponseStatus(423, "Locked");

	/**
	 * 424 Failed Dependency (WebDAV, RFC4918)
	 */
	public static final HttpResponseStatus FAILED_DEPENDENCY = new HttpResponseStatus(424, "Failed Dependency");

	/**
	 * 425 Unordered Collection (WebDAV, RFC3648)
	 */
	public static final HttpResponseStatus UNORDERED_COLLECTION = new HttpResponseStatus(425, "Unordered Collection");

	/**
	 * 426 Upgrade Required (RFC2817)
	 */
	public static final HttpResponseStatus UPGRADE_REQUIRED = new HttpResponseStatus(426, "Upgrade Required");

	/**
	 * 428 Precondition Required (RFC6585)
	 */
	public static final HttpResponseStatus PRECONDITION_REQUIRED = new HttpResponseStatus(428, "Precondition Required");

	/**
	 * 429 Too Many Requests (RFC6585)
	 */
	public static final HttpResponseStatus TOO_MANY_REQUESTS = new HttpResponseStatus(429, "Too Many Requests",false);

	/**
	 * 431 Request Header Fields Too Large (RFC6585)
	 */
	public static final HttpResponseStatus REQUEST_HEADER_FIELDS_TOO_LARGE = new HttpResponseStatus(431, "Request Header Fields Too Large",false);
	
	public static final HttpResponseStatus REQUEST_INVALID_CONTENT_LENGTH = new HttpResponseStatus(481, "Request Content-Length Not Integer",false);
	public static final HttpResponseStatus REQUEST_INVALID_CHUNKED_SIZE = new HttpResponseStatus(482, "Request Invalid Chunked Size",false);
	public static final HttpResponseStatus REQUEST_CLIENT_CLOSE = new HttpResponseStatus(483, "Request Client Closed",false);


	/**
	 * 500 Internal Server Error
	 */
	public static final HttpResponseStatus INTERNAL_SERVER_ERROR = new HttpResponseStatus(500, "Internal Server Error");

	/**
	 * 501 Not Implemented
	 */
	public static final HttpResponseStatus NOT_IMPLEMENTED = new HttpResponseStatus(501, "Not Implemented");

	/**
	 * 502 Bad Gateway
	 */
	public static final HttpResponseStatus BAD_GATEWAY = new HttpResponseStatus(502, "Bad Gateway");

	/**
	 * 503 Service Unavailable
	 */
	public static final HttpResponseStatus SERVICE_UNAVAILABLE = new HttpResponseStatus(503, "Service Unavailable");

	/**
	 * 504 Gateway Timeout
	 */
	public static final HttpResponseStatus GATEWAY_TIMEOUT = new HttpResponseStatus(504, "Gateway Timeout");

	/**
	 * 505 HTTP Version Not Supported
	 */
	public static final HttpResponseStatus HTTP_VERSION_NOT_SUPPORTED = new HttpResponseStatus(505, "HTTP Version Not Supported");

	/**
	 * 506 Variant Also Negotiates (RFC2295)
	 */
	public static final HttpResponseStatus VARIANT_ALSO_NEGOTIATES = new HttpResponseStatus(506, "Variant Also Negotiates");

	/**
	 * 507 Insufficient Storage (WebDAV, RFC4918)
	 */
	public static final HttpResponseStatus INSUFFICIENT_STORAGE = new HttpResponseStatus(507, "Insufficient Storage");

	/**
	 * 510 Not Extended (RFC2774)
	 */
	public static final HttpResponseStatus NOT_EXTENDED = new HttpResponseStatus(510, "Not Extended");

	/**
	 * 511 Network Authentication Required (RFC6585)
	 */
	public static final HttpResponseStatus NETWORK_AUTHENTICATION_REQUIRED = new HttpResponseStatus(511, "Network Authentication Required");

}
