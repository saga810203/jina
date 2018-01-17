package org.jfw.jina.http.server;

import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.impl.DefaultHttpParameters;
import org.jfw.jina.http.server.HttpRequest.HttpMethod;
import org.jfw.jina.util.StringUtil;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.NioAsyncChannel;

public class HttpChannel extends NioAsyncChannel<HttpAsyncExecutor> {
	private enum State {
		SKIP_CONTROL_CHARS ,
		READ_INITIAL ,
		READ_HEADER ,
		READ_VARIABLE_LENGTH_CONTENT ,
		READ_FIXED_LENGTH_CONTENT ,
		READ_CHUNK_SIZE ,
		READ_CHUNKED_CONTENT ,
		READ_CHUNK_DELIMITER
	}

	private Node keepAliveNode;
	private Queue keepAliveQueue;
	private long keepAliveTimeout;

	protected HttpServerRequest request = null;
	protected HttpServerResponse response = null;
	protected LineParser lineParser = new LineParser(8192);

	protected HttpService service = new HttpService();

	public HttpChannel(HttpAsyncExecutor executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
	}

	@Override
	protected void afterRegister() {
		this.keepAliveQueue = this.executor.getKeepAliveQueue();
		this.keepAliveTimeout = this.executor.getKeepAliveTimeout();
	}

	public HttpChannel(NioAsyncChannel<? extends HttpAsyncExecutor> channel) {
		super(channel);
	}

	public HttpService getService() {
		return service;
	}

	public void setService(HttpService service) {
		this.service = service;
	}

	public void removeKeepAliveCheck() {
		if (this.keepAliveNode != null) {
			this.keepAliveQueue.remove(this.keepAliveNode);
		}
	}

	public void addKeepAliveCheck() {
		if (this.keepAliveNode != null) {
			this.keepAliveQueue.remove(this.keepAliveNode);
		}
		this.keepAliveNode = this.keepAliveQueue.add(System.currentTimeMillis() + this.keepAliveTimeout);
	}

	@Override
	public void close() {
		this.closeJavaChannel();
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

	private State readHeaders(InputBuf buffer) {
		int lineSize = lineParser.parseLine(buffer);
		if (lineSize < 0) {
			return null;
		} else if (lineSize > 0) {
			do {
				if (lineSize >= lineParser.maxLength) {
					this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
					return null;
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
					return null;
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
		State nextState;
		HttpMethod method = this.request.method;
		if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
			contentLength = 0;
			nextState = State.READ_FIXED_LENGTH_CONTENT;
		} else if (this.isTransferEncodingChunked()) {
			nextState = State.READ_CHUNK_SIZE;
		} else {
			long cl = contentLength();
			if (cl == Long.MIN_VALUE) {
				this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CONTENT_LENGTH);
				return null;
			} else if (cl >= 0) {
				nextState = State.READ_FIXED_LENGTH_CONTENT;
			} else {
				nextState = State.READ_VARIABLE_LENGTH_CONTENT;
			}
		}
		this.request.doAfterHeaderParse();
		return nextState;
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

	private State currentState = State.SKIP_CONTROL_CHARS;
	private long contentLength = Long.MAX_VALUE;
	private long chunkSize = 0;

	protected void resetRequest() {
		this.request = null;
		this.response = null;
		this.currentState = State.SKIP_CONTROL_CHARS;
		this.contentLength = Long.MAX_VALUE;
		this.chunkSize = 0;
		this.cleanOpRead();
	}

	protected void handleInvalidHttpresutst(HttpResponseStatus error) {
		this.request = null;
		if (this.request != null) {
			request.requestExecutor.error(request, error.getCode());
		}
		this.response = null;
		this.contentLength = Long.MAX_VALUE;
		this.chunkSize = Long.MAX_VALUE;
		this.currentState = State.READ_FIXED_LENGTH_CONTENT;
		OutputBuf buf = executor.alloc();
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

	protected State readRequestLine(InputBuf buffer) {
		int size = lineParser.parseLine(buffer);
		if (size < 0) {
			return null;
		} else if (size >= lineParser.maxLength) {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_URI_TOO_LONG);
			return null;
		} else {
			char[] seq = lineParser.seq;
			int start;
			int end;
			int len;
			HttpMethod method = null;
			start = StringUtil.findNonWhitespace(seq, 0, size);
			if (start >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return null;
			}
			end = StringUtil.findWhitespace(seq, start, size);
			if (end >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return null;
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
				return null;
			}
			end = StringUtil.findWhitespace(seq, start, size);
			if (end >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return null;
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
				return null;
			}

			uri = StringUtil.normalize(seq, start, end);
			if (uri == null) {
				this.handleInvalidHttpresutst(HttpResponseStatus.METHOD_NOT_ALLOWED);
				return null;
			}

			start = StringUtil.findNonWhitespace(seq, end, size);
			if (start >= size) {
				this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
				return null;
			}
			end = StringUtil.findEndOfString(seq, start, size);
			if ((end - start == 8) && StringUtil.equals(HttpConsts.HTTP11, 0, seq, start, 8)) {
				this.request = this.newRequest(method, uri, qs, hs);
				this.response = this.request.response;
				return State.READ_HEADER;
			}
			this.handleInvalidHttpresutst(HttpResponseStatus.BAD_REQUEST);
			return null;
		}
	}

	@Override
	protected void handleRead(InputBuf buf, int len) {
		State nextState = null;
		if (len > 0) {
			for (;;) {
				switch (currentState) {
				case SKIP_CONTROL_CHARS: {
					if (!buf.skipControlCharacters()) {
						return;
					}
					if (this.request != null) {
						this.handleInvalidHttpresutst(HttpResponseStatus.TOO_MANY_REQUESTS);
						return;
					}
					currentState = State.READ_INITIAL;
				}
				case READ_INITIAL: {
					nextState = this.readRequestLine(buf);
					if (nextState == null) {
						return;
					}
				}
				case READ_HEADER: {
					nextState = readHeaders(buf);
					if (nextState == null) {
						return;
					}
					currentState = nextState;
					if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
						chunkSize = contentLength;
					}
					service.service(request);
				}
				case READ_VARIABLE_LENGTH_CONTENT: {
					if (buf.readable()) {
						request.requestExecutor.requestBody(request, buf);
					}
					return;
				}
				case READ_FIXED_LENGTH_CONTENT: {
					if (chunkSize == 0) {
						this.request.requestExecutor.execute(request, response);
						this.resetRequest();
					} else {
						long nr = buf.readableBytes();
						if (nr > chunkSize) {
							InputBuf dbuf = buf.duplicate((int) chunkSize);
							this.request.requestExecutor.requestBody(request, dbuf);
							dbuf.release();
							buf.skipBytes((int) chunkSize);
							this.request.requestExecutor.execute(request, response);
							this.resetRequest();
						} else {
							this.request.requestExecutor.requestBody(request, buf);
							chunkSize -= nr;
						}
					}
				}
				case READ_CHUNK_SIZE: {
					int lineSize = lineParser.parseLine(buf);
					if (lineSize < 0) {
						return;
					} else if (lineSize >= lineParser.maxLength) {
						this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_SIZE);
						return;
					}
					this.chunkSize = lineParser.getChunkSize(lineSize);
					if (chunkSize == Integer.MIN_VALUE) {
						this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_INVALID_CHUNKED_SIZE);
						return;
					}
					if (chunkSize == 0) {
						this.request.requestExecutor.execute(request, response);
						this.resetRequest();
					} else {
						currentState = State.READ_CHUNKED_CONTENT;
					}
				}
				case READ_CHUNKED_CONTENT: {
					assert chunkSize <= Integer.MAX_VALUE;
					long nr = buf.readableBytes();
					if (nr > chunkSize) {
						InputBuf dbuf = buf.duplicate((int) chunkSize);
						this.request.requestExecutor.requestBody(request, dbuf);
						dbuf.release();
						buf.skipBytes((int) chunkSize);
						this.currentState = State.READ_CHUNK_DELIMITER;
					} else if (nr > 0) {
						this.request.requestExecutor.requestBody(request, buf);
						chunkSize -= nr;
						if (chunkSize == 0) {
							this.currentState = State.READ_CHUNK_DELIMITER;
							return;
						}

					}
				}
				case READ_CHUNK_DELIMITER: {
					while (buf.readable()) {
						if (buf.readByte() == 10) {
							currentState = State.READ_CHUNK_SIZE;
							break;
						}
					}
					if (currentState == State.READ_CHUNK_DELIMITER) {
						return;
					}
				}
				}
			}
		} else {
			this.handleCloseInput();
		}
	}

	protected void handleCloseInput() {
		switch (currentState) {
		case SKIP_CONTROL_CHARS: {
			this.write(EmptyBuf.INSTANCE, closeTask);
		}
		case READ_INITIAL: {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
		}
		case READ_HEADER: {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
		}
		case READ_VARIABLE_LENGTH_CONTENT: {
			this.request.requestExecutor.execute(request, response);
		}
		case READ_FIXED_LENGTH_CONTENT:
		case READ_CHUNK_SIZE:
		case READ_CHUNKED_CONTENT:
		case READ_CHUNK_DELIMITER: {
			this.handleInvalidHttpresutst(HttpResponseStatus.REQUEST_CLIENT_CLOSE);
		}
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

		public void doAfterHeaderParse() {
			this.response.requestKeepAlive = this.headers.isKeepAlive();
		}

		@Override
		public void setBodyBuilder(RequestExecutor builder) {
			this.requestExecutor = builder;
		}

		@Override
		public HttpHeaders headers() {
			return this.headers();
		}

	}

	protected class HttpServerResponse implements HttpResponse {
		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		protected int state = STATE_INIT;
		protected boolean requestKeepAlive = true;
		protected HttpResponseStatus hrs = HttpResponseStatus.OK;

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
			OutputBuf buf = executor.alloc();
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
		public void addBody(InputBuf buf) {
			if (state == STATE_INIT) {
				OutputBuf obuf = this.sendResponseLineAndHeader();
				write(obuf.input());
				obuf.release();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (buf.readable()) {
				write(buf, null);
			} else {
				buf.release();
			}
		}

		@Override
		public void flush(InputBuf buf, AsyncTask task) {
			write(buf, new ResponseCloseTask(this.requestKeepAlive && this.hrs.isKeepAlive(), task));
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
			OutputBuf buf = this.sendResponseLineAndHeader();
			if (cl > 0) {
				buf = writeBytes(buf, content, 0, content.length);
			}
			write(buf.input(), pkeepAlive ? beginRead : closeTask);
			buf.release();
		}

		@Override
		public int state() {
			return this.state;
		}

	}

	public void keepAliveTimeout() {
		this.closeJavaChannel();
	}

	protected class ResponseCloseTask implements AsyncTask {
		protected boolean keepAlive;
		protected AsyncTask task;

		public ResponseCloseTask(boolean keepAlive, AsyncTask task) {
			this.keepAlive = keepAlive;
			this.task = task;
		}

		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
		}

		@Override
		public void completed(AsyncExecutor executor) {
			if (keepAlive) {
				addKeepAliveCheck();
			} else {
				close();
			}
			if (task != null) {
				task.completed(executor);
			}
		}

		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}

		@Override
		public void cancled(AsyncExecutor executor) {
			removeKeepAliveCheck();
			if (task != null) {
				task.cancled(executor);
			}

		}

	}

	protected final AsyncTask closeTask = new AsyncTask() {

		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}

		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
		}

		@Override
		public void completed(AsyncExecutor executor) {
			close();
		}

		@Override
		public void cancled(AsyncExecutor executor) {
		}
	};
	protected final AsyncTask beginRead = new AsyncTask() {
		@Override
		public void execute(AsyncExecutor executor) throws Throwable {
		}

		@Override
		public void completed(AsyncExecutor executor) {
			setOpRead();
			addKeepAliveCheck();
		}

		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}

		@Override
		public void cancled(AsyncExecutor executor) {
		}
	};
}
