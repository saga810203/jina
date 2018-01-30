package org.jfw.jina.http.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
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
import org.jfw.jina.util.TagQueue;
import org.jfw.jina.util.TagQueue.TagNode;

public class HttpChannel extends AbstractNioAsyncChannel<HttpAsyncExecutor> implements KeepAliveCheck {

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
	protected LineParser lineParser = new LineParser(8192);
	protected TagQueue<InputBuf, Integer> inputCache;

	protected HttpService service = new HttpService();

	public HttpChannel(HttpAsyncExecutor executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
		this.inputCache = executor.newTagQueue();
	}

	@Override
	protected void afterRegister() {
		this.keepAliveNode = this.executor.newDNode(this);
		this.addKeepAliveCheck();
	}

	public HttpChannel(AbstractNioAsyncChannel<? extends HttpAsyncExecutor> channel) {
		super(channel);
		this.inputCache = executor.newTagQueue();
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
		if (inputCache.isEmpty()) {
			this.currentState = HTTP_STATE_SKIP_CONTROL_CHARS;
			if (this.addKeepAliveCheck()) {
				this.setOpRead();
			}
		} else {
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
			handleInputCache();
		}
	};

	protected boolean handleFixLenthRead(InputBuf buf) {
		if (chunkSize == 0) {
			this.requestInvoke();
			return true;
		}
		int nr = buf.readableBytes();
		if (nr == 0)
			return false;
		if (nr > chunkSize) {
			InputBuf dbuf = buf.duplicate((int) chunkSize);
			this.requestBodyRead(dbuf);
			dbuf.release();
			buf.skipBytes((int) chunkSize);
			this.requestInvoke();
			return true;
		} else {
			InputBuf ibuf = buf.slice();
			this.requestBodyRead(ibuf);
			ibuf.release();
			buf.skipAllBytes();
			chunkSize -= nr;
			if (chunkSize == 0) {
				this.requestInvoke();
				return true;
			}
			return false;
		}
	}

	protected void handleInputCache() {
		TagNode input = null;
		InputBuf buf = null;
		int len = -1;
		this.currentState = HTTP_STATE_SKIP_CONTROL_CHARS;
		while ((input = this.inputCache.peekTagNode()) != null) {
			buf = (InputBuf) input.item();
			len = ((Integer) input.tag()).intValue();
			if (len > 0) {
				for (;;) {
					if (currentState == HTTP_STATE_SKIP_CONTROL_CHARS) {
						if (!this.doSkipControlChars(buf)) {
							break;
						}
					}
					if (currentState == HTTP_STATE_READ_INITIAL) {
						if (!this.doReadRequestLine(buf)) {
							break;
						}
					}
					if (currentState == HTTP_STATE_READ_HEADER) {
						if (!this.doReadHeaders(buf)) {
							this.addKeepAliveCheck();
							break;
						}
					}
					if (currentState == HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT) {
						if (buf.readable()) {
							InputBuf ibuf = buf.slice();
							this.requestBodyRead(ibuf);
							ibuf.release();
							buf.skipAllBytes();
						}
						break;
					}
					if (currentState == HTTP_STATE_READ_FIXED_LENGTH_CONTENT) {
						if (!this.handleFixLenthRead(buf)) {
							break;
						}
					}
					if (currentState == HTTP_STATE_READ_CHUNK_SIZE || currentState == HTTP_STATE_READ_CHUNKED_CONTENT
							|| currentState == HTTP_STATE_READ_CHUNK_DELIMITER
							|| currentState == HTTP_STATE_READ_CHUNK_FOOTER) {
						if (!this.readInChunked(buf)) {
							break;
						}
					}

					if (currentState == HTTP_STATE_INVOKING) {
						int unReadSize = buf.readableBytes();
						if (unReadSize == 0) {
							buf.release();
							inputCache.unsafeShift();
						}
						return;
					}
					if (currentState == HTTP_STATE_IGNORE) {
						inputCache.clear(RELEASE_INPUT_BUF);
						return;
					}
				}
				buf.release();
				inputCache.unsafeShift();
			} else {
				this.inputCache.clear(RELEASE_INPUT_BUF);
				this.handleCloseInput();
				return;
			}
		}
		if (null != request && request.supended) {
			this.cleanOpRead();
		} else if (this.addKeepAliveCheck()) {
			this.setOpRead();
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
		this.inputCache.clear(RELEASE_INPUT_BUF);
		if (this.response != null && this.response.cacheOutputBuf != null) {
			this.response.cacheOutputBuf.release();
			this.response.cacheOutputBuf = null;
		}
	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();
	}

	private String hName;
	private String hValue;

	private void splitHeader(char[] seq, int end) {
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;

		nameStart = StringUtil.findNonWhitespace(seq, 0, end);
		for (nameEnd = nameStart; nameEnd < end; nameEnd++) {
			char ch = seq[nameEnd];
			if (ch == ':' || Character.isWhitespace(ch)) {
				break;
			}
		}

		for (colonEnd = nameEnd; colonEnd < end; colonEnd++) {
			if (seq[colonEnd] == ':') {
				colonEnd++;
				break;
			}
		}

		hName = new String(seq, nameStart, nameEnd - nameStart);
		valueStart = StringUtil.findNonWhitespace(seq, 0, colonEnd);
		if (valueStart == end) {
			hValue = StringUtil.EMPTY_STRING;
		} else {
			valueEnd = StringUtil.findEndOfString(seq, 0, end);
			hValue = new String(seq, valueStart, valueEnd - valueStart);
		}
	}

	private boolean doReadHeaders(InputBuf buffer) {
		int lineSize = lineParser.parseLine(buffer);
		if (lineSize < 0) {
			return false;
		} else if (lineSize > 0) {
			do {
				if (lineSize >= lineParser.maxLength) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
					return true;
				}
				char[] seq = lineParser.seq;
				char firstChar = seq[0];
				if (hName != null && (firstChar == ' ' || firstChar == '\t')) {
					// please do not make one line from below code
					// as it breaks +XX:OptimizeStringConcat optimization
					hValue = hValue + ' ' + StringUtil.trim(lineParser.seq, 0, lineSize);
				} else {
					if (hName != null) {
						this.request.headers.add(hName, hValue);
					}
					splitHeader(seq, lineSize);
				}

				lineSize = lineParser.parseLine(buffer);
				if (lineSize < 0) {
					return false;
				}
			} while (lineSize > 0);
		}

		// Add the last header.
		if (hName != null) {
			this.request.headers.add(hName, hValue);
		}
		// reset name and value fields
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

	private boolean readTrailingHeaders(InputBuf buf) {
		// AppendableCharSequence line = headerParser.parse(buffer);
		int lineSize = lineParser.parseLine(buf);
		if (lineSize < 0) {
			return false;
		} else if (lineSize > 0) {
			do {
				if (lineSize >= lineParser.maxLength) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_FOOTER);
					return true;
				}

				// TODO: IGNORE

				lineSize = lineParser.parseLine(buf);
				if (lineSize < 0) {
					return false;
				}
			} while (lineSize > 0);
		}
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
			OutputBuf buf = executor.allocBuffer();
			byte[] bs = error.getReason();
			buf = this.writeAscii(buf, "HTTP/1.1 " + error.getCode() + ' ');
			buf = this.writeBytes(buf, bs, 0, bs.length);
			buf = this.writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			buf = this.writeHttpHeader(buf, HttpConsts.CONTENT_LENGTH, Integer.toString(bs.length));
			buf = this.writeHttpHeader(buf, HttpConsts.CONTENT_TYPE, HttpConsts.TEXT_HTML_UTF8);
			buf = this.writeHttpHeader(buf, HttpConsts.DATE, executor.dateFormatter.httpDateHeaderValue());
			buf = this.writeHttpHeader(buf, HttpConsts.CONNECTION, HttpConsts.CLOSE);
			buf = this.writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			buf = this.writeBytes(buf, bs, 0, bs.length);
			this.write(buf.input(), closeTask);
			buf.release();
		}
	}

	protected OutputBuf writeHttpHeader(OutputBuf buf, String name, String value) {
		int sBegin = 0;
		int sEnd = value.length();
		int lineIdx = name.length();
		buf = this.writeAscii(buf, name);
		buf = this.writeByte(buf, ':');
		if (lineIdx + sEnd <= 1022) {
			buf = this.writeAscii(buf, value);
			buf = this.writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
		} else {
			int len = 1022 - lineIdx;
			int nEnd = sBegin + len;
			buf = this.writeAscii(buf, value.substring(sBegin, nEnd));
			buf = this.writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			do {
				sBegin = sEnd;
				nEnd += 1021;
				buf = this.writeByte(buf, '\t');
				buf = this.writeAscii(buf, value.substring(sBegin, Integer.min(sEnd, nEnd)));
				buf = this.writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			} while (nEnd < sEnd);
		}
		return buf;
	}

	protected boolean doReadRequestLine(InputBuf buffer) {
		int size = lineParser.parseLine(buffer);
		if (size < 0) {
			return false;
		} else if (size >= lineParser.maxLength) {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_URI_TOO_LONG);
			return true;
		} else {
			char[] seq = lineParser.seq;
			int start;
			int end;
			int len;
			HttpMethod method = null;
			start = StringUtil.findNonWhitespace(seq, 0, size);
			if (start >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return true;
			}
			end = StringUtil.findWhitespace(seq, start, size);
			if (end >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return true;
			}
			len = end - start;
			if (len == 3) {
				if (StringUtil.equals(HttpConsts.GET_CHAR_ARRAY, 0, seq, start, 3)) {
					method = HttpMethod.GET;
				} else if (StringUtil.equals(HttpConsts.PUT_CHAR_ARRAY, 0, seq, start, 3)) {
					method = HttpMethod.PUT;
				}
			} else if (len == 4 && StringUtil.equals(HttpConsts.POST_CHAR_ARRAY, 0, seq, start, 4)) {
				method = HttpMethod.POST;
			} else if (len == 6 && StringUtil.equals(HttpConsts.DELETE_CHAR_ARRAY, 0, seq, start, 6)) {
				method = HttpMethod.DELETE;
			}
			if (method == null) {
				this.handleInvalidHttpresutst(HttpResponseStatus.METHOD_NOT_ALLOWED);
				return true;
			}
			end = StringUtil.findWhitespace(seq, start, size);
			if (end >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return true;
			}
			int qStart = 0;
			int hStart = Integer.MAX_VALUE;
			String qs = null; // QueryString
			String hs = null; // HashString
			String uri = null;
			int pEnd = end;
			if (seq[start] == '/' || seq[start] == '\\') {
				for (int i = start + 1; i < end; ++i) {
					if (seq[i] == '?') {
						qStart = i;
						pEnd = i;
						break;
					}
				}
				for (int i = Integer.max(start, qStart) + 1; i < end; ++i) {
					if (seq[i] == '#') {
						hStart = i;
						pEnd = Integer.min(pEnd, i);
						break;
					}
				}
				uri = StringUtil.normalize(seq, start, pEnd);
				if (qStart > 0) {
					++qStart;
					int qEnd = Integer.min(end, hStart);
					if (qStart < qEnd) {
						qs = new String(seq, qStart, qEnd - qStart);
					}
				}
				if (hStart < Integer.MAX_VALUE) {
					++hStart;
					if (hStart < end) {
						hs = new String(seq, hStart, end - qStart);
					}
				}
			} else {
				this.handleInvalidHttpresutst(HttpResponseStatus.METHOD_NOT_ALLOWED);
				return true;
			}

			uri = StringUtil.normalize(seq, start, end);
			if (uri == null) {
				this.handleInvalidHttpresutst(HttpResponseStatus.METHOD_NOT_ALLOWED);
				return true;
			}

			start = StringUtil.findNonWhitespace(seq, end, size);
			if (start >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return true;
			}
			end = StringUtil.findEndOfString(seq, start, size);
			if ((end - start == 8) && StringUtil.equals(HttpConsts.HTTP11, 0, seq, start, 8)) {
				this.request = this.newRequest(method, uri, qs, hs);
				this.response = this.request.response;
				this.currentState = HTTP_STATE_READ_HEADER;
				return true;
			}
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return true;
		}
	}

	protected void configRequest() {
		try {
			service.service(request);
			request.requestExecutor.setAsyncExecutor(executor);
		} catch (Throwable e) {
			handleInternalException(e);
		}
	}

	protected void requestBodyRead(InputBuf ibuf) {
		try {
			request.requestExecutor.requestBody(this.request, ibuf);
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
		buf = StringUtil
				.utf8(HttpResponseStatus.buildHtmlPage(HttpResponseStatus.INTERNAL_SERVER_ERROR, sw.toString()));
		response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
		response.unsafeContentLength(buf.length);
		response.unsafeWrite(buf, 0, buf.length);
		response.unsafeFlush();
		request.setRequestExecutor(IGRONE_EXECUTOR);
	}

	private boolean doSkipControlChars(InputBuf buf) {
		if (!buf.skipControlCharacters()) {
			return false;
		}
		if (this.request != null) {
			this.handleInvalidHttpresutst(HttpResponseStatus.TOO_MANY_REQUESTS);
			return true;
		}
		currentState = HTTP_STATE_READ_INITIAL;
		return true;
	}

	private boolean readInChunked(InputBuf buf) {
		for (;;) {
			if (currentState == HTTP_STATE_READ_CHUNK_SIZE) {
				int lineSize = lineParser.parseLine(buf);
				if (lineSize < 0) {
					return false;
				} else if (lineSize >= lineParser.maxLength) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_SIZE);
					return true;
				}
				this.chunkSize = lineParser.getChunkSize(lineSize);
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
				long nr = buf.readableBytes();
				if (nr > chunkSize) {
					InputBuf dbuf = buf.duplicate((int) chunkSize);
					this.requestBodyRead(dbuf);
					dbuf.release();
					buf.skipBytes((int) chunkSize);
					this.currentState = HTTP_STATE_READ_CHUNK_DELIMITER;
				} else if (nr > 0) {
					InputBuf dBuf = buf.slice();
					this.requestBodyRead(dBuf);
					dBuf.release();
					buf.skipAllBytes();
					chunkSize -= nr;
					if (chunkSize == 0) {
						this.currentState = HTTP_STATE_READ_CHUNK_DELIMITER;
					}
					return false;
				} else {
					return false;
				}
			}
			if (currentState == HTTP_STATE_READ_CHUNK_DELIMITER) {
				while (buf.readable()) {
					if (buf.readByte() == 10) {
						currentState = HTTP_STATE_READ_CHUNK_SIZE;
						break;
					}
				}
				if (currentState == HTTP_STATE_READ_CHUNK_DELIMITER) {
					return false;
				}
			}
			if (currentState == HTTP_STATE_READ_CHUNK_FOOTER) {
				return this.readTrailingHeaders(buf);
			}
		}
	}

	@Override
	protected void handleRead(InputBuf buf, int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
			for (;;) {
				if (currentState == HTTP_STATE_SKIP_CONTROL_CHARS) {
					if (!this.doSkipControlChars(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_INITIAL) {
					if (!this.doReadRequestLine(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_HEADER) {
					if (!this.doReadHeaders(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_VARIABLE_LENGTH_CONTENT) {
					if (buf.readable()) {
						InputBuf ibuf = buf.slice();
						this.requestBodyRead(ibuf);
						ibuf.release();
						buf.skipAllBytes();
					}
					if (!request.supended) {
						this.addKeepAliveCheck();
					} else {
						this.cleanOpRead();
					}
					return;
				}
				if (currentState == HTTP_STATE_READ_FIXED_LENGTH_CONTENT) {
					if (!this.handleFixLenthRead(buf)) {
						if (request.supended) {
							this.cleanOpRead();
						} else {
							this.addKeepAliveCheck();
						}
						return;
					}
				}
				if (currentState == HTTP_STATE_READ_CHUNK_SIZE || currentState == HTTP_STATE_READ_CHUNKED_CONTENT
						|| currentState == HTTP_STATE_READ_CHUNK_DELIMITER
						|| currentState == HTTP_STATE_READ_CHUNK_FOOTER) {
					if (!this.readInChunked(buf)) {
						if (request.supended) {
							this.cleanOpRead();
						} else {
							this.addKeepAliveCheck();
						}
						return;
					}
				}

				if (currentState == HTTP_STATE_INVOKING) {
					int unReadSize = buf.readableBytes();
					if (unReadSize > 0) {
						this.inputCache.offer(buf.retain(), unReadSize);
					}
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
				this.write(EmptyBuf.INSTANCE, closeTask);
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
				this.inputCache.offer(EmptyBuf.INSTANCE, -1);
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

	private static class LineParser implements ByteProcessor {
		private final char[] seq;
		private final int maxLength;
		private int size;

		LineParser(int maxLength) {
			this.seq = new char[maxLength];
			this.maxLength = maxLength;
		}

		public int parse(InputBuf buffer) {
			int i = buffer.forEachByte(this);
			if (i > 0) {
				if (size >= maxLength) {
					return size;
				}
				buffer.skipBytes(i + 1);
			} else if (i == 0) {
				buffer.skipBytes(1);
			}
			return i;
		}

		// public String[] parseHttpLine(InputBuf buffer) throws Exception {
		// int idx = parse(buffer);
		// if (idx < 0) {
		// return null;
		// }
		// String[] ret = this.splitInitialLine();
		// this.size = 0;
		// return ret;
		// }

		public int parseLine(InputBuf buffer) {
			int idx = parse(buffer);
			if (idx < 0) {
				return -1;
			}
			int ret = this.size;
			this.size = 0;
			return ret;
		}

		@Override
		public boolean process(byte value) {
			char nextByte = (char) (value & 0xFF);
			if (nextByte == HttpConsts.CR) {
				return true;
			}
			if (nextByte == HttpConsts.LF) {
				return false;
			}
			seq[size++] = nextByte;
			if (size >= maxLength) {
				return true;
			}
			return true;
		}

		public int getChunkSize(int length) {
			String hex = StringUtil.trim(seq, 0, length);
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

		private OutputBuf cacheOutputBuf = null;

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

		protected OutputBuf sendResponseLineAndHeader() {
			boolean pkeepAlive = this.requestKeepAlive && this.hrs.isKeepAlive();
			if (!pkeepAlive)
				this.headers.set(HttpConsts.CONNECTION, HttpConsts.CLOSE);
			if (null == headers.get(HttpConsts.CONTENT_LENGTH)) {
				this.headers.set(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			}
			byte[] bs = hrs.getDefautContent();
			OutputBuf buf = executor.allocBuffer();
			buf = writeAscii(buf, "HTTP/1.1 " + hrs.getCode() + ' ');
			buf = writeBytes(buf, bs, 0, bs.length);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			for (java.util.Map.Entry<String, String> entry : this.headers) {
				buf = writeHttpHeader(buf, entry.getKey(), entry.getValue());
			}
			buf = writeBytes(buf, HttpConsts.CRLF, 0, HttpConsts.CRLF.length);
			return buf;
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
			if (this.cacheOutputBuf != null) {
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			}
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
			OutputBuf buf = this.sendResponseLineAndHeader();
			if (cl > 0) {
				buf = writeBytes(buf, content, 0, content.length);
			}
			HttpChannel.this.write(buf.input(), pkeepAlive ? beginRead : closeTask);
			buf.release();
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
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			OutputBuf buf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					chunkedBuffer, idx, chunkedBuffer.length - idx);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
			buf = writeBytes(buf, buffer, index, length);
			this.cacheOutputBuf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
		}

		@Override
		public void flush(byte[] buffer, int index, int length) {
			assert buffer != null && buffer.length > 0;
			assert index >= 0;
			assert length > 0;
			assert buffer.length >= (index + length);
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			OutputBuf buf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					chunkedBuffer, idx, chunkedBuffer.length - idx);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
			buf = writeBytes(buf, buffer, index, length);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
			buf = writeBytes(buf, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0,
					HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length);
			HttpChannel.this.write(buf.input(), this.defaultFlushTask());
			buf.release();
			this.cacheOutputBuf = null;
		}

		public void flush() {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}

			OutputBuf buf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length);
			HttpChannel.this.write(buf.input(), this.defaultFlushTask());
			buf.release();
			this.cacheOutputBuf = null;
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
			byte[] chunkedBuffer = executor.ouputCalcBuffer;
			int idx = StringUtil.toUnsignedString(chunkedBuffer, length, 4);
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			OutputBuf buf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					chunkedBuffer, idx, chunkedBuffer.length - idx);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
			buf = writeBytes(buf, buffer, index, length);
			buf = writeBytes(buf, HttpConsts.CRLF, 0, 2);
			buf = writeBytes(buf, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0,
					HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length);
			HttpChannel.this.write(buf.input(), this.wrap(task));
			buf.release();
			this.cacheOutputBuf = null;
		}

		@Override
		public void flush(TaskCompletionHandler task) {
			assert task != null;
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) == null;
			assert this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			OutputBuf buf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER, 0, HttpConsts.CHUNKED_ZERO_AND_CHUNKED_FOOTER.length);
			HttpChannel.this.write(buf.input(), this.wrap(task));
			buf.release();
			this.cacheOutputBuf = null;
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
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			this.cacheOutputBuf = writeBytes(this.cacheOutputBuf != null ? this.cacheOutputBuf : executor.allocBuffer(),
					buffer, index, length);
		}

		@Override
		public void unsafeWirte(InputBuf buffer, TaskCompletionHandler task) {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			assert buffer != null;
			assert buffer.readable();
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (this.cacheOutputBuf != null) {
				HttpChannel.this.write(this.cacheOutputBuf.input());
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			}
			HttpChannel.this.write(buffer, task);
		}

		@Override
		public void unsafeFlush(InputBuf buffer, TaskCompletionHandler task) {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			assert buffer != null;
			assert buffer.readable();
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}

			if (this.cacheOutputBuf != null) {
				HttpChannel.this.write(this.cacheOutputBuf.input());
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			}
			HttpChannel.this.write(buffer, wrap(task));
		}

		@Override
		public void unsafeFlush(InputBuf buffer) {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			assert buffer != null;
			assert buffer.readable();
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (this.cacheOutputBuf != null) {
				HttpChannel.this.write(this.cacheOutputBuf.input());
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			}
			HttpChannel.this.write(buffer, defaultFlushTask());
		}

		@Override
		public void unsafeFlush(TaskCompletionHandler task) {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			assert task != null;
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (this.cacheOutputBuf != null) {
				HttpChannel.this.write(this.cacheOutputBuf.input(), defaultFlushTask());
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			} else {
				HttpChannel.this.write(EmptyBuf.INSTANCE, wrap(task));
			}
		}

		@Override
		public void unsafeFlush() {
			assert this.headers.get(HttpConsts.CONTENT_LENGTH) != null;
			assert !this.headers.containsIgnoreCase(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			if (state == STATE_INIT) {
				this.cacheOutputBuf = this.sendResponseLineAndHeader();
				state = STATE_SENDED;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (this.cacheOutputBuf != null) {
				HttpChannel.this.write(this.cacheOutputBuf.input(), defaultFlushTask());
				this.cacheOutputBuf.release();
				this.cacheOutputBuf = null;
			} else {
				HttpChannel.this.write(EmptyBuf.INSTANCE, defaultFlushTask());
			}
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
		public void requestBody(HttpRequest request, InputBuf buf) {
			buf.skipAllBytes();
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
