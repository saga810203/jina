package org.jfw.jina.http.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.AsyncTaskAdapter;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.core.impl.AbstractNioAsyncChannel;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.impl.DefaultHttpParameters;
import org.jfw.jina.http.server.HttpRequest.HttpMethod;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;
import org.jfw.jina.util.DQueue.DNode;
import org.jfw.jina.util.StringUtil;

public class HttpChannel<T extends HttpAsyncExecutor> extends AbstractNioAsyncChannel<T> implements KeepAliveCheck {

	private static final int HTTP_STATE_SKIP_CONTROL_CHARS = 10;
	private static final int HTTP_STATE_READ_INITIAL = 20;
	private static final int HTTP_STATE_READ_HEADER = 30;
	private static final int HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT = 40;
	private static final int HTTP_STATE_READ_FIXED_LENGTH_CONTENT = 50;
	private static final int HTTP_STATE_READ_CHUNK_SIZE = 60;
	private static final int HTTP_STATE_READ_CHUNKED_CONTENT = 63;
	private static final int HTTP_STATE_READ_CHUNK_DELIMITER = 65;
	private static final int HTTP_STATE_READ_CHUNK_FOOTER = 67;
	private static final int HTTP_STATE_INVOKING = 90;
	private static final int HTTP_STATE_IGNORE = -1;

	private DNode keepAliveNode;
	private long keepAliveTimeout;

	protected HttpServerRequest request = null;
	protected HttpServerResponse response = null;

	protected HttpService service = new HttpService();

	int lineEnd;

	public HttpChannel(T executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
	}

	@Override
	protected void afterRegister() {
		this.keepAliveNode = this.executor.newDNode(this);
		this.addKeepAliveCheck();
	}

	public HttpChannel(AbstractNioAsyncChannel<? extends T> channel) {
		super(channel);
	}

	public HttpService getService() {
		return service;
	}

	public void setService(HttpService service) {
		this.service = service;
	}

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue();
			return true;
		}
		return false;
	}

	public boolean addKeepAliveCheck() {
		if (this.keepAliveTimeout == Long.MAX_VALUE) {
			this.keepAliveTimeout = System.currentTimeMillis();
			this.keepAliveNode.enqueue(this.executor.getKeepAliveQueue());
			return true;
		}
		return false;
	}

	protected void beginRead() {
		assert this.currentState == HTTP_STATE_INVOKING;
		this.contentLength = Long.MAX_VALUE;
		this.chunkSize = 0;
		this.currentState = HTTP_STATE_SKIP_CONTROL_CHARS;
		if (this.widx == this.ridx) {
			this.clearReadBuffer();
			this.setOpRead();
		} else {
			this.cleanOpRead();
			// TagNode node = inputCache.peekTagNode();
			// InputBuf buf = (InputBuf)node.item();
			// int len = ((Integer)node.tag()).intValue();
			// if(!buf.readable()){
			//
			// } 可能存在两个调用周期才会 setOpRead();

			// 延迟处理防止调用栈太长，完合遵守HTTP1.1不会出现这种问题
			executor.submit(handReadDelay);
		}

	}

	protected final AsyncTask handReadDelay = new AsyncTaskAdapter() {
		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
			int len = widx - ridx;
			handleRead(len);
		}
	};

	protected boolean handleFixLenthRead() {
		if (chunkSize == 0) {
			this.requestBodyRead(0, true);
			this.compactReadBuffer();
			this.requestInvoke();
			return true;
		}
		int nr = this.widx - this.ridx;
		if (nr == 0) {
			this.clearReadBuffer();
			return false;
		} else if (nr > chunkSize) {
			this.requestBodyRead((int) chunkSize, true);
			this.ridx += chunkSize;
			this.compactReadBuffer();
			return true;
		} else if (chunkSize == nr) {
			this.requestBodyRead(nr, true);
			this.clearReadBuffer();
			this.requestInvoke();
			return true;
		} else {
			this.requestBodyRead(nr, false);
			this.clearReadBuffer();
			chunkSize -= nr;
			return false;
		}

	}

	@Override
	public void close() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue();
			this.executor.freeDNode(this.keepAliveNode);
			this.keepAliveNode = null;
		}
		this.closeJavaChannel();
	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();
	}

	private String hName;
	private String hValue;

	private void splitHeader(int start) {
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;

		nameStart = StringUtil.findNonWhitespace(this.rBytes, start, lineEnd);
		for (nameEnd = nameStart; nameEnd < lineEnd; nameEnd++) {
			byte ch = rBytes[nameEnd];
			if (ch == ':' || Character.isWhitespace(ch)) {
				break;
			}
		}

		for (colonEnd = nameEnd; colonEnd < lineEnd; colonEnd++) {
			if (rBytes[colonEnd] == ':') {
				colonEnd++;
				break;
			}
		}

		hName = new String(this.rBytes, nameStart, nameEnd - nameStart, StringUtil.US_ASCII);
		valueStart = StringUtil.findNonWhitespace(this.rBytes, colonEnd, lineEnd);
		if (valueStart == lineEnd) {
			hValue = StringUtil.EMPTY_STRING;
		} else {
			valueEnd = StringUtil.findEndOfString(this.rBytes, colonEnd, lineEnd);
			hValue = new String(this.rBytes, valueStart, valueEnd - valueStart, StringUtil.US_ASCII);
		}
	}

	private int parseLine() {
		int begin = this.ridx;
		for (int i = begin; i < this.widx; ++i) {
			if (this.rBytes[i] == '\n') {
				lineEnd = i;
				this.ridx = i + 1;
				if (this.rBytes[i - 1] == '\r') {
					lineEnd = i - 1;
				}
				return begin;
			}
		}
		if (this.widx >= this.rlen) {
			if (this.ridx == 0) {
				return Integer.MAX_VALUE;
			} else {
				this.compactReadBuffer();
			}
		}
		return -1;

	}

	private boolean doReadHeaders() {
		int start = parseLine();
		if (start < 0) {
			if (this.ridx > 0) {
				this.compactReadBuffer();
			}
			return false;
		} else if (start == Integer.MAX_VALUE) {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
			this.clearReadBuffer();
			return true;
		} else if (lineEnd - start > 0) {
			do {
				char firstChar = (char) rBytes[start];
				if (hName != null && (firstChar == ' ' || firstChar == '\t')) {
					// please do not make one line from below code
					// as it breaks +XX:OptimizeStringConcat optimization
					hValue = hValue + ' ' + StringUtil.trim(rBytes, start, lineEnd);
				} else {
					if (hName != null) {
						this.request.headers.add(hName, hValue);
					}
					splitHeader(start);
				}

				start = parseLine();
				if (start < 0) {
					if (this.ridx > 0) {
						this.compactReadBuffer();
					}
					return false;
				} else if (start == Integer.MAX_VALUE) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
					this.clearReadBuffer();
					return true;
				}
			} while (lineEnd - start > 0);
		}

		if (hName != null) {
			this.request.headers.add(hName, hValue);
		}
		hName = null;
		hValue = null;
		HttpMethod method = this.request.method;
		if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
			contentLength = 0;
			chunkSize = 0;
			this.currentState = HTTP_STATE_READ_FIXED_LENGTH_CONTENT;
		} else if (this.isTransferEncodingChunked()) {
			this.currentState = HTTP_STATE_READ_CHUNK_SIZE;
		} else {
			long cl = contentLength();
			if (cl == Long.MIN_VALUE) {
				this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CONTENT_LENGTH);
				return true;
			} else if (cl >= 0) {
				this.currentState = HTTP_STATE_READ_FIXED_LENGTH_CONTENT;
			} else {
				this.response.requestKeepAlive = false;
				this.currentState = HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT;
				this.configRequest();
				return true;
			}
		}
		this.response.requestKeepAlive = this.request.headers.isKeepAlive();
		this.configRequest();
		return true;
	}

	private boolean readTrailingHeaders() {
		// AppendableCharSequence line = headerParser.parse(buffer);
		int start = parseLine();
		if (start < 0) {
			return false;
		} else if (start == Integer.MAX_VALUE) {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_FOOTER);
			this.clearReadBuffer();
			return true;
		} else if (lineEnd - start > 0) {
			do {
				// TODO: IGNORE
				start = parseLine();
				if (start < 0) {
					return false;
				} else if (start == Integer.MAX_VALUE) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_FOOTER);
					this.clearReadBuffer();
					return true;
				}
			} while (lineEnd - start > 0);
		}
		this.requestBodyRead(0, true);
		this.compactReadBuffer();
		this.requestInvoke();
		return true;
	}

	private long contentLength() {
		if (contentLength == Long.MIN_VALUE) {
			String val = this.request.headers.get(HttpConsts.CONTENT_LENGTH);
			if (val != null) {
				try {
					contentLength = Long.parseLong(val);
				} catch (NumberFormatException e) {
					contentLength = Long.MIN_VALUE;
				}
			}
		}
		return contentLength;
	}

	private boolean isTransferEncodingChunked() {
		String val = this.request.headers.get(HttpConsts.TRANSFER_ENCODING);
		return val != null && val.equalsIgnoreCase(HttpConsts.CHUNKED);
	}

	private int currentState = HTTP_STATE_SKIP_CONTROL_CHARS;
	private long contentLength = Long.MAX_VALUE;
	private long chunkSize = 0;

	protected void handleInvalidHttpresutst(HttpResponseStatus error) {
		if (this.currentState != HTTP_STATE_IGNORE) {
			if (this.request != null) {
				request.requestExecutor.error(request, error.getCode());
			}
			this.request = null;
			this.response = null;
			this.currentState = HTTP_STATE_IGNORE;
			byte[] bs = error.getReason();
			byte[] lbs = ("HTTP/1.1 " + error.getCode() + ' ').getBytes(StringUtil.US_ASCII);
			this.writeData(lbs, 0, lbs.length);
			this.writeData(bs, 0, bs.length);
			this.writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			this.writeHttpHeader(HttpConsts.CONTENT_LENGTH, Integer.toString(bs.length));
			this.writeHttpHeader(HttpConsts.CONTENT_TYPE, HttpConsts.TEXT_HTML_UTF8);
			this.writeHttpHeader(HttpConsts.DATE, executor.dateFormatter.httpDateHeaderValue());
			this.writeHttpHeader(HttpConsts.CONNECTION, HttpConsts.CLOSE);
			this.writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			this.flushData(bs, 0, bs.length, closeTask);
		}
	}

	protected void writeHttpHeader(String name, String value) {
		int sBegin = 0;
		int sEnd = value.length();
		int lineIdx = name.length();

		byte[] bs = name.getBytes(StringUtil.US_ASCII);
		this.writeData(bs, 0, bs.length);
		this.writeByteData((byte) ':');
		if (lineIdx + sEnd <= 1022) {
			bs = value.getBytes(StringUtil.US_ASCII);
			this.writeData(bs, 0, bs.length);
			this.writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
		} else {
			int len = 1022 - lineIdx;
			int nEnd = sBegin + len;
			bs = value.substring(sBegin, nEnd).getBytes(StringUtil.US_ASCII);

			this.writeData(bs, 0, bs.length);
			this.writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			do {
				sBegin = sEnd;
				nEnd += 1021;
				this.writeByteData((byte) '\t');
				bs = value.substring(sBegin, Integer.min(sEnd, nEnd)).getBytes(StringUtil.US_ASCII);
				this.writeData(bs, 0, bs.length);
				this.writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			} while (nEnd < sEnd);
		}
	}

	protected boolean doReadRequestLine() {
		int start = this.parseLine();
		if (start == Integer.MAX_VALUE) {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_URI_TOO_LONG);
			this.clearReadBuffer();
			return true;
		}

		HttpMethod method = null;
		start = StringUtil.findNonWhitespace(this.rBytes, start, lineEnd);
		if (start >= lineEnd) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}
		int fEnd = StringUtil.findWhitespace(this.rBytes, start, lineEnd);
		if (fEnd >= lineEnd) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}
		int len = fEnd - start;
		if (len == 3) {
			if (StringUtil.equals(HttpConsts.GET_BYTE_ARRAY, 0, this.rBytes, start, 3)) {
				method = HttpMethod.GET;
			} else if (StringUtil.equals(HttpConsts.PUT_BYTE_ARRAY, 0, rBytes, start, 3)) {
				method = HttpMethod.PUT;
			}
		} else if (len == 4 && StringUtil.equals(HttpConsts.POST_BYTE_ARRAY, 0, rBytes, start, 4)) {
			method = HttpMethod.POST;
		} else if (len == 6 && StringUtil.equals(HttpConsts.DELETE_BYTE_ARRAY, 0, rBytes, start, 6)) {
			method = HttpMethod.DELETE;
		}
		if (method == null) {
			this.handleInvalidHttpresutst(HttpResponseStatus.METHOD_NOT_ALLOWED);
			return true;
		}

		start = StringUtil.findNonWhitespace(rBytes, fEnd, lineEnd);
		if (start >= lineEnd) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}
		fEnd = StringUtil.findWhitespace(this.rBytes, start, lineEnd);
		if (fEnd >= lineEnd) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}

		int qStart = 0;
		int hStart = Integer.MAX_VALUE;
		String qs = null; // QueryString
		String hs = null; // HashString
		String uri = null;
		int pEnd = fEnd;
		if (this.rBytes[start] == '/' || rBytes[start] == '\\') {
			for (int i = start + 1; i < fEnd; ++i) {
				if (rBytes[i] == '?') {
					qStart = i;
					pEnd = i;
					break;
				}
			}
			for (int i = Integer.max(start, qStart) + 1; i < fEnd; ++i) {
				if (rBytes[i] == '#') {
					hStart = i;
					pEnd = Integer.min(pEnd, i);
					break;
				}
			}
			uri = StringUtil.normalize(rBytes, start, pEnd);
			if (qStart > 0) {
				++qStart;
				int qEnd = Integer.min(fEnd, hStart);
				if (qStart < qEnd) {
					qs = new String(rBytes, qStart, qEnd - qStart);
				}
			}
			if (hStart < Integer.MAX_VALUE) {
				++hStart;
				if (hStart < fEnd) {
					hs = new String(rBytes, hStart, fEnd - qStart);
				}
			}
		} else {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}

		if (uri == null) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}

		start = StringUtil.findNonWhitespace(rBytes, fEnd, lineEnd);
		if (start >= lineEnd) {
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}
		fEnd = StringUtil.findEndOfString(rBytes, start, lineEnd);
		if ((fEnd - start == 8) && StringUtil.equals(HttpConsts.HTTP11_BYTE_ARRAY, 0, rBytes, start, 8)) {
			this.request = this.newRequest(method, uri, qs, hs);
			this.response = this.request.response;
			this.currentState = HTTP_STATE_READ_HEADER;
			return true;
		}
		this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
		return true;
	}

	protected void configRequest() {
		try {
			service.service(request);
			request.requestExecutor.setAsyncExecutor(executor);
		} catch (Throwable e) {
			handleInternalException(e);
		}
	}

	protected void requestBodyRead(int len, boolean end) {
		try {
			request.requestExecutor.requestBody(this.request, this.rBytes, this.ridx, len, end);
		} catch (Throwable e) {
			handleInternalException(e);
		}
	}

	protected void requestInvoke() {
		HttpServerRequest req = request;
		HttpServerResponse res = response;
		this.request = null;
		this.response = null;
		this.currentState = HTTP_STATE_INVOKING;
		try {
			req.requestExecutor.execute(req, res);
		} catch (Throwable e) {
			if (res.state == HttpResponse.STATE_INIT) {
				handleInternalException(e);
				IGRONE_EXECUTOR.execute(req, res);
			} else {
				this.close();
				return;
			}
		}
	}

	protected void handleInternalException(Throwable e) {
		byte[] buf = null;
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		buf = StringUtil.utf8(HttpResponseStatus.buildHtmlPage(HttpResponseStatus.INTERNAL_SERVER_ERROR, sw.toString()));
		response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
		response.unsafeContentLength(buf.length);
		response.unsafeWrite(buf, 0, buf.length);
//		response.unsafeFlush();
		request.setRequestExecutor(IGRONE_EXECUTOR);
	}

	private boolean doSkipControlChars() {
		while (widx > ridx) {
			int c = (int) (this.rBytes[this.ridx++] & 0xFF);
			if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
				ridx--;
				currentState = HTTP_STATE_READ_INITIAL;
				return true;
			}
		}
		this.clearReadBuffer();
		return false;
	}

	public int getChunkSize(int start) {
		String hex = StringUtil.trim(this.rBytes, start, lineEnd);
		for (int i = 0; i < hex.length(); i++) {
			char c = hex.charAt(i);
			if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
				hex = hex.substring(0, i);
				break;
			}
		}
		try {
			return Integer.parseInt(hex, 16);
		} catch (NumberFormatException e) {
			return Integer.MIN_VALUE;
		}
	}

	private boolean readInChunked() {
		for (;;) {
			if (currentState == HTTP_STATE_READ_CHUNK_SIZE) {
				int start = this.parseLine();
				if (start < 0) {
					return false;
				} else if (start == Integer.MAX_VALUE) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_SIZE);
					return true;
				}
				this.chunkSize = getChunkSize(start);
				if (chunkSize == Integer.MIN_VALUE) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_SIZE);
					return true;
				}
				if (chunkSize == 0) {
					currentState = HTTP_STATE_READ_CHUNK_FOOTER;
				} else {
					currentState = HTTP_STATE_READ_CHUNKED_CONTENT;
				}
			}
			if (currentState == HTTP_STATE_READ_CHUNKED_CONTENT) {
				assert chunkSize <= Integer.MAX_VALUE;
				long nr = this.widx - this.ridx;
				if (nr > chunkSize) {
					this.requestBodyRead((int) chunkSize, false);
					this.ridx += chunkSize;
					this.currentState = HTTP_STATE_READ_CHUNK_DELIMITER;
				} else if (nr > 0) {
					this.requestBodyRead((int) nr, false);
					chunkSize -= nr;
					if (chunkSize == 0) {
						this.currentState = HTTP_STATE_READ_CHUNK_DELIMITER;
					}
					this.compactReadBuffer();
					return false;
				} else {
					this.compactReadBuffer();
					return false;
				}
			}
			if (currentState == HTTP_STATE_READ_CHUNK_DELIMITER) {
				while (this.ridx < this.widx) {
					if (this.rBytes[this.ridx++] == 10) {
						this.currentState = HTTP_STATE_READ_CHUNK_SIZE;
					}
				}
				if (currentState == HTTP_STATE_READ_CHUNK_DELIMITER) {
					this.clearReadBuffer();
					return false;
				}
			}
			if (currentState == HTTP_STATE_READ_CHUNK_FOOTER) {
				return this.readTrailingHeaders();
			}
		}
	}

	@Override
	protected void handleRead(int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
			for (;;) {
				if (currentState == HTTP_STATE_SKIP_CONTROL_CHARS) {
					if (!this.doSkipControlChars()) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_INITIAL) {
					if (!this.doReadRequestLine()) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_HEADER) {
					if (!this.doReadHeaders()) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT) {
					int dlen = this.widx - this.ridx;
					if (len > 0) {
						this.requestBodyRead(dlen, false);
						this.clearReadBuffer();
					}
					if (!request.supended) {
						this.addKeepAliveCheck();
					} else {
						this.cleanOpRead();
					}
					return;
				}
				if (currentState == HTTP_STATE_READ_FIXED_LENGTH_CONTENT) {
					if (!this.handleFixLenthRead()) {
						if (request.supended) {
							this.cleanOpRead();
						} else {
							this.addKeepAliveCheck();
						}
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_CHUNK_SIZE || currentState == HTTP_STATE_READ_CHUNKED_CONTENT
						|| currentState == HTTP_STATE_READ_CHUNK_DELIMITER || currentState == HTTP_STATE_READ_CHUNK_FOOTER) {
					if (!this.readInChunked()) {
						if (request.supended) {
							this.cleanOpRead();
						} else {
							this.addKeepAliveCheck();
						}
						return;
					}
				}
				if (currentState == HTTP_STATE_INVOKING) {
					this.cleanOpRead();
					return;
				}
				if (currentState == HTTP_STATE_IGNORE) {
					return;
				}
			}
		} else {
			this.cleanOpRead();
			this.handleCloseInput();
		}
	}

	protected void handleCloseInput() {
		switch (currentState) {
			case HTTP_STATE_SKIP_CONTROL_CHARS: {
				this.flushData(closeTask);
				break;
			}
			case HTTP_STATE_READ_INITIAL: {
				this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
				break;
			}
			case HTTP_STATE_READ_HEADER: {
				this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
				break;
			}
			case HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT: {
				this.requestBodyRead(0, true);
				this.requestInvoke();
				break;
			}
			case HTTP_STATE_READ_FIXED_LENGTH_CONTENT:
			case HTTP_STATE_READ_CHUNK_SIZE:
			case HTTP_STATE_READ_CHUNKED_CONTENT:
			case HTTP_STATE_READ_CHUNK_DELIMITER:
			case HTTP_STATE_READ_CHUNK_FOOTER: {
				this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
				break;
			}
			case HTTP_STATE_INVOKING: {
				// this.inputCache.offer(EmptyBuf.INSTANCE, -1);
				break;
			}
			// case HTTP_STATE_IGNORE:{
			// this.write(EmptyBuf.INSTANCE, closeTask);
			// break;
			// }
		}
	}

	protected HttpServerRequest cacheRequest = new HttpServerRequest();

	protected HttpServerRequest newRequest(HttpMethod method, String uri, String queryString, String hash) {
		cacheRequest.headers.clear();
		cacheRequest.path = uri;
		cacheRequest.queryString = queryString;
		cacheRequest.hash = queryString;
		cacheRequest.response.state = HttpResponse.STATE_INIT;
		cacheRequest.response.headers.clear().add(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
		cacheRequest.response.hrs = HttpResponseStatus.OK;
		cacheRequest.supended = false;
		return cacheRequest;
	}

	protected class HttpServerRequest implements HttpRequest {
		protected HttpMethod method;
		protected String path;
		protected String queryString;
		protected String hash;
		protected RequestExecutor requestExecutor;
		protected boolean supended;

		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		protected DefaultHttpParameters parameters = new DefaultHttpParameters();
		protected HttpServerResponse response;

		public HttpServerRequest() {
			this.response = new HttpServerResponse();
		}

		protected HttpServerResponse response() {
			return this.response;
		}

		@Override
		public HttpMethod method() {
			return this.method;
		}

		@Override
		public String path() {
			return this.path;
		}

		@Override
		public String queryString() {
			return this.queryString;
		}

		@Override
		public String hash() {
			return this.hash;
		}

		@Override
		public void setRequestExecutor(RequestExecutor builder) {
			this.requestExecutor = builder;
		}

		@Override
		public void abort() {
			if (request != null) {
				if (response.state == HttpResponse.STATE_INIT) {
					handleInternalException(new RuntimeException("service abort"));
					IGRONE_EXECUTOR.execute(request, response);
				} else {
					close();
				}
			}
		}

		@Override
		public HttpHeaders headers() {
			return this.headers();
		}

		@Override
		public void suspendRead() {
			if (request != null)
				this.supended = true;
		}

		@Override
		public void resumeRead() {
			if (request != null) {
				this.supended = false;
				setOpRead();
				addKeepAliveCheck();
			}
		}

	}

	protected class HttpServerResponse implements HttpResponse {
		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		protected int state = STATE_INIT;
		protected boolean requestKeepAlive = true;
		protected HttpResponseStatus hrs = HttpResponseStatus.OK;

		protected TaskCompletionHandler wrap(TaskCompletionHandler task) {
			return new ResponseCloseTask(this.requestKeepAlive && this.hrs.isKeepAlive(), task);
		}

		public TaskCompletionHandler defaultFlushTask() {
			return (this.requestKeepAlive && this.hrs.isKeepAlive()) ? beginRead : closeTask;
		}

		@Override
		public void addHeader(String name, String value) {
			this.headers.add(name, value);
		}

		protected void sendResponseLineAndHeader() {
			boolean pkeepAlive = this.requestKeepAlive && this.hrs.isKeepAlive();
			if (!pkeepAlive)
				this.headers.set(HttpConsts.CONNECTION, HttpConsts.CLOSE);
			if (null == headers.get(HttpConsts.CONTENT_LENGTH)) {
				this.headers.set(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			}
			byte[] bs = hrs.getDefautContent();
			byte[] lbs = ("HTTP/1.1 " + hrs.getCode() + ' ').getBytes(StringUtil.US_ASCII);
			writeData(lbs, 0, lbs.length);
			writeData(bs, 0, bs.length);
			writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			for (java.util.Map.Entry<String, String> entry : this.headers) {
				writeHttpHeader(entry.getKey(), entry.getValue());
			}
			writeData(HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
		}

		@Override
		public void setStatus(HttpResponseStatus httpResponseStatus) {
			if (this.state == STATE_INIT) {
				this.hrs = httpResponseStatus;
			} else {
				throw new IllegalStateException();
			}
		}

		@Override
		public void fail() {
			close();
		}

		@Override
		public void sendClientError(HttpResponseStatus error) {
			boolean pkeepAlive = this.requestKeepAlive && error.isKeepAlive();
			if (state != STATE_INIT) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			byte[] content = error.getDefautContent();
			int cl = content.length;
			this.hrs = error;
			this.headers.clear();
			if (!pkeepAlive)
				this.headers.add(HttpConsts.CONNECTION, HttpConsts.CLOSE);
			this.headers.add(HttpConsts.CONTENT_LENGTH, Integer.toString(cl));
			this.headers.add(HttpConsts.CONTENT_TYPE, HttpConsts.TEXT_HTML_UTF8);
			this.sendResponseLineAndHeader();
			if (cl > 0) {
				flushData(content, 0, content.length, pkeepAlive ? beginRead : closeTask);
			} else {
				flushData(pkeepAlive ? beginRead : closeTask);
			}
		}

		@Override
		public int state() {
			return this.state;
		}

		@Override
		public void write(byte[] buffer, int index, int length) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			writeData(chunkedBuffer, idx, chunkedBuffer.length - idx);
			writeData(HttpConsts.CRLF, 0, 2);
			writeData(buffer, index, length);
			writeData(HttpConsts.CRLF, 0, 2);
		}

		@Override
		public void write(byte[] buffer, int index, int length, TaskCompletionHandler task) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			writeData(chunkedBuffer, idx, chunkedBuffer.length - idx);
			writeData(HttpConsts.CRLF, 0, 2);
			writeData(buffer, index, length);
			flushData(HttpConsts.CRLF, 0, 2, task);

		}

		@Override
		public void flush(byte[] buffer, int index, int length) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);

			if (state == STATE_INIT) {
				state = STATE_SENDED;
				this.headers.remove(HttpConsts.TRANSFER_ENCODING);
				this.headers.add(HttpConsts.CONTENT_LENGTH, Integer.toString(length));
				this.sendResponseLineAndHeader();
				flushData(buffer, index, length, this.defaultFlushTask());
				return;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			writeData(chunkedBuffer, idx, chunkedBuffer.length - idx);
			writeData(HttpConsts.CRLF, 0, 2);
			writeData(buffer, index, length);
			writeData(HttpConsts.CRLF, 0, 2);
			flushData(HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length, this.defaultFlushTask());
		}

		public void flush() {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				state = STATE_SENDED;
				this.headers.remove(HttpConsts.TRANSFER_ENCODING);
				this.headers.add(HttpConsts.CONTENT_LENGTH, StringUtil.ZERO_STRING);
				this.sendResponseLineAndHeader();
				flushData(this.defaultFlushTask());
				return;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length, this.defaultFlushTask());
		}

		@Override
		public void flush(byte[] buffer, int index, int length, TaskCompletionHandler task) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert task != null;
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);

			if (state == STATE_INIT) {
				state = STATE_SENDED;
				this.headers.remove(HttpConsts.TRANSFER_ENCODING);
				this.headers.add(HttpConsts.CONTENT_LENGTH, Integer.toString(length));
				this.sendResponseLineAndHeader();
				flushData(buffer, index, length, this.wrap(task));

				return;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			writeData(chunkedBuffer, idx, chunkedBuffer.length - idx);
			writeData(HttpConsts.CRLF, 0, 2);
			writeData(buffer, index, length);
			writeData(HttpConsts.CRLF, 0, 2);
			flushData(HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length, this.wrap(task));

		}

		@Override
		public void flush(TaskCompletionHandler task) {
			assert task != null;
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				state = STATE_SENDED;
				this.headers.remove(HttpConsts.TRANSFER_ENCODING);
				this.headers.add(HttpConsts.CONTENT_LENGTH, StringUtil.ZERO_STRING);
				sendResponseLineAndHeader();
				flushData(this.wrap(task));
				return;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length, this.wrap(task));
		}

		@Override
		public void unsafeContentLength(long length) {
			assert (state == STATE_INIT);
			headers.remove(HttpConsts.TRANSFER_ENCODING);
			headers.add(HttpConsts.CONTENT_LENGTH, Long.toString(length));
		}

		@Override
		public void unsafeWrite(byte[] buffer, int index, int length) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			writeData(buffer, index, length);
		}

		@Override
		public void unsafeWrite(byte[] buffer, int index, int length, TaskCompletionHandler task) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert task != null;
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);

			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			flushData(buffer, index, length, task);
		}

		@Override
		public void unsafeFlush(byte[] buffer, int index, int length, TaskCompletionHandler task) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert task != null;
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();

			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(buffer, index, length, wrap(task));
		}

		@Override
		public void unsafeFlush(byte[] buffer, int index, int length) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(buffer, index, length, defaultFlushTask());
		}

		@Override
		public void unsafeFlush(TaskCompletionHandler task) {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			assert task != null;
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(wrap(task));
		}

		@Override
		public void unsafeFlush() {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();

			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			state = STATE_SENDED;
			flushData(defaultFlushTask());
		}
	}

	public void keepAliveTimeout() {
		this.close();
	}

	protected class ResponseCloseTask implements TaskCompletionHandler {
		protected boolean keepAlive;
		protected TaskCompletionHandler task;

		public ResponseCloseTask(boolean keepAlive, TaskCompletionHandler task) {
			this.keepAlive = keepAlive;
			this.task = task;
		}

		@Override
		public void completed(AsyncExecutor executor) {
			try {
				task.completed(executor);
			} catch (Throwable e) {
			}
			if (keepAlive) {
				beginRead();
			} else {
				close();
			}
		}

		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
			try {
				this.task.failed(exc, executor);
			} catch (Throwable e) {
				// Ignore
			}
		}
	}

	protected final TaskCompletionHandler closeTask = new TaskCompletionHandler() {
		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}

		@Override
		public void completed(AsyncExecutor executor) {
			close();
		}

	};
	protected final TaskCompletionHandler beginRead = new TaskCompletionHandler() {
		@Override
		public void completed(AsyncExecutor executor) {
			beginRead();
		}

		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}
	};

	protected final RequestExecutor IGRONE_EXECUTOR = new RequestExecutor() {
		public void setAsyncExecutor(HttpAsyncExecutor executor) {
		}

		@Override
		public void requestBody(HttpRequest request, byte[] b, int i, int l, boolean a) {

		}

		@Override
		public void execute(HttpRequest request, HttpResponse response) {
			response.unsafeFlush();
		}

		@Override
		public void error(HttpRequest request, int code) {
		}
	};

	@Override
	public long getKeepAliveTime() {
		return this.keepAliveTimeout;
	}

}
