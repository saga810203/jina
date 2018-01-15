package org.jfw.jina.http.server;

import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.exception.TooLongFrameException;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.impl.DefaultHttpParameters;
import org.jfw.jina.http.server.HttpRequest.HttpMethod;
import org.jfw.jina.util.StringUtil;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.NioAsyncChannel;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.LinkedNode;

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

	protected void handleRequest() {
		this.service.service(request);
		request.builder.begin(request, request.parameters);
	}

	@Override
	public void close() {
		this.closeJavaChannel();
	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();

	}

	protected HttpServerRequest createInvalidHttpRequest(Exception e) {
		this.currentState = State.READ_FIXED_LENGTH_CONTENT;
		this.contentLength = Long.MAX_VALUE;
		// TODO impl

		if (currentState == State.READ_INITIAL) {
			if (e instanceof TooLongFrameException) {

			} else {
				// error method
				// error uri
				// error version
				// ....
			}
		} else if (currentState == State.READ_HEADER) {
			if (e instanceof NumberFormatException) {
				// error header name:content-length
			}
		}

		currentState = State.READ_FIXED_LENGTH_CONTENT;
		contentLength = Long.MAX_VALUE;

		return null;
	}

	protected HttpServerRequest createRequest(String[] line) {
		// TODO impl
		return null;
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

	private State readHeaders(InputBuf buffer) throws Exception {
		int lineSize = lineParser.parseLine(buffer);
		if (lineSize < 0) {
			return null;
		}
		if (lineSize > 0) {
			do {
				char[] seq = lineParser.seq;
				char firstChar = seq[0];
				if (hName != null && (firstChar == ' ' || firstChar == '\t')) {
					// please do not make one line from below code
					// as it breaks +XX:OptimizeStringConcat optimization
					hValue = hValue + ' ' + StringUtil.trim(lineParser.seq, 0, lineSize);
				} else {
					if (hName != null) {
						this.request.headers.put(hName, hValue);
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
			this.request.headers.put(hName, hValue);
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
		} else if (contentLength() >= 0) {
			nextState = State.READ_FIXED_LENGTH_CONTENT;
		} else {
			nextState = State.READ_VARIABLE_LENGTH_CONTENT;
		}
		return nextState;
	}

	private long contentLength() {
		if (contentLength == Long.MIN_VALUE) {
			String val = this.request.headers.get(HttpConsts.CONTENT_LANGUAGE);
			if (val != null) {
				contentLength = Long.parseLong(val);
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
		this.currentState = State.SKIP_CONTROL_CHARS;
		this.contentLength = Long.MAX_VALUE;
		this.chunkSize = 0;
	}

	@Override
	protected void handleRead(InputBuf buf, int len) {
		if (len > 0) {
			for (;;) {
				switch (currentState) {
				case SKIP_CONTROL_CHARS: {
					if (!buf.skipControlCharacters()) {
						return;
					}
					currentState = State.READ_INITIAL;
				}
				case READ_INITIAL:
					try {
						String[] line = lineParser.parseHttpLine(buf);
						if (line == null) {
							return;
						}
						if (line.length < 3) {
							// Invalid initial line - ignore.
							currentState = State.SKIP_CONTROL_CHARS;
							return;
						}
						this.request = this.createRequest(line);
					} catch (Exception e) {
						this.request = this.createInvalidHttpRequest(e);
						this.handleRequest();
						return;
					}

				case READ_HEADER:
					try {
						State nextState = readHeaders(buf);
						if (nextState == null) {
							return;
						}
						currentState = nextState;
						if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
							chunkSize = contentLength;
						}
						this.handleRequest();
					} catch (Exception e) {
						this.request = this.createInvalidHttpRequest(e);
						this.handleRequest();
						return;
					}
				case READ_VARIABLE_LENGTH_CONTENT: {
					// Keep reading data as a chunk until the end of connection
					// is reached.
					if (buf.readable()) {
						request.builder.handleBody(request, buf);
					}
					return;
				}
				case READ_FIXED_LENGTH_CONTENT: {
					if (chunkSize == 0) {
						this.request.builder.end(request, request.response(), true);
						this.resetRequest();
					} else {
						long nr = buf.readableBytes();
						if (nr > chunkSize) {
							InputBuf dbuf = buf.duplicate((int) chunkSize);
							this.request.builder.handleBody(request, dbuf);
							dbuf.release();
							buf.skipBytes((int) chunkSize);
							this.request.builder.end(request, request.response(), true);
							this.resetRequest();
							this.currentState = State.SKIP_CONTROL_CHARS;
						} else {
							this.request.builder.handleBody(request, buf);
							chunkSize -= nr;
						}
					}
				}

				case READ_CHUNK_SIZE:
					try {
						int lineSize = lineParser.parseLine(buf);
						if (lineSize < 0) {
							return;
						}
						int chunkSize = lineParser.getChunkSize(lineSize);
						this.chunkSize = chunkSize;
						if (chunkSize == 0) {
							this.request.builder.end(request, request.response(), true);
							this.resetRequest();
							currentState = State.SKIP_CONTROL_CHARS;
						} else {
							currentState = State.READ_CHUNKED_CONTENT;
						}
					} catch (Exception e) {
						this.request.builder.end(request, request.response(), false);
						return;
					}
				case READ_CHUNKED_CONTENT: {
					assert chunkSize <= Integer.MAX_VALUE;
					long nr = buf.readableBytes();
					if (nr > chunkSize) {
						InputBuf dbuf = buf.duplicate((int) chunkSize);
						this.request.builder.handleBody(request, dbuf);
						dbuf.release();
						buf.skipBytes((int) chunkSize);
						this.currentState = State.READ_CHUNK_DELIMITER;
					} else {
						this.request.builder.handleBody(request, buf);
						chunkSize -= nr;
						if (chunkSize == 0) {
							this.currentState = State.READ_CHUNK_DELIMITER;
							return;
						}
					}
				}
				case READ_CHUNK_DELIMITER: {
					while (buf.readable()) {
						if (buf.readableBytes() == 10) {
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
		}
		case READ_INITIAL:{}

		case READ_HEADER:{}
		case READ_VARIABLE_LENGTH_CONTENT: {
			this.request.builder.end(request, request.response(),true);
		}
		case READ_FIXED_LENGTH_CONTENT:
		case READ_CHUNK_SIZE:
		case READ_CHUNKED_CONTENT: 
		case READ_CHUNK_DELIMITER: {
			this.request.builder.end(request, request.response(),false);
		}
		}
	}

	private static class LineParser implements ByteProcessor {
		private final char[] seq;
		private final int maxLength;
		private int size;

		LineParser(int maxLength) {
			this.seq = new char[maxLength];
			this.maxLength = maxLength;
		}

		public int parse(InputBuf buffer) throws Exception {
			int i = buffer.forEachByte(this);
			if (i > 0) {
				if (size >= maxLength) {
					throw new TooLongFrameException();
				}
				buffer.skipBytes(i + 1);
			} else if (i == 0) {
				buffer.skipBytes(1);
			}
			return i;
		}

		public String[] parseHttpLine(InputBuf buffer) throws Exception {
			int idx = parse(buffer);
			if (idx < 0) {
				return null;
			}
			String[] ret = this.splitInitialLine();
			this.size = 0;
			return ret;
		}

		public int parseLine(InputBuf buffer) throws Exception {
			int idx = parse(buffer);
			if (idx < 0) {
				return -1;
			}
			int ret = this.size;
			this.size = 0;
			return ret;
		}

		public String[] splitInitialLine() {
			int aStart;
			int aEnd;
			int bStart;
			int bEnd;
			int cStart;
			int cEnd;

			aStart = StringUtil.findNonWhitespace(seq, 0, this.size);
			aEnd = StringUtil.findWhitespace(seq, aStart, this.size);

			bStart = StringUtil.findNonWhitespace(seq, aEnd, this.size);
			bEnd = StringUtil.findWhitespace(seq, bStart, this.size);

			cStart = StringUtil.findNonWhitespace(seq, bEnd, this.size);
			cEnd = StringUtil.findEndOfString(seq, 0, this.size);

			return new String[] { new String(seq, aStart, aEnd - aStart), new String(seq, bStart, bEnd - bStart),
					cStart < cEnd ? new String(seq, cStart, cEnd - cStart) : StringUtil.EMPTY_STRING };
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
			return Integer.parseInt(hex, 16);
		}
	}

	protected class HttpServerRequest implements HttpRequest {
		protected HttpMethod method;
		protected String path;
		protected String queryString;
		protected String hash;
		protected HttpRequestBodyBuilder builder;

		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		protected DefaultHttpParameters parameters = new DefaultHttpParameters();
		protected HttpServerResponse response;

		public HttpServerRequest() {
			this.response = new HttpServerResponse();
		}

		protected HttpServerResponse newResponse() {
			return new HttpServerResponse();
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
		public HttpResponse response() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void setBodyBuilder(HttpRequestBodyBuilder builder) {
			this.builder = builder;
		}
	}

	protected class HttpServerResponse implements HttpResponse {

		LinkedNode begin = executor.getNode();

		@Override
		public void addHeader(String name, String value) {
			// TODO Auto-generated method stub

		}

		@Override
		public void setStatus(int sc) {
			// TODO Auto-generated method stub

		}

		@Override
		public void addBody(InputBuf buf) {
			// TODO Auto-generated method stub

		}

		@Override
		public void flush(AsyncTask task) {
			// TODO Auto-generated method stub

		}

	}

	public void keepAliveTimeout() {
		this.closeJavaChannel();
	}

}
