package org.jfw.jina.http.server;

import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.EmptyBuf;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpParameters;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.exception.TooLongFrameException;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.impl.DefaultHttpParameters;
import org.jfw.jina.http.server.HttpRequest.HttpMethod;
import org.jfw.jina.util.StringUtil;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.NioAsyncChannel;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.LinkedNode;
import org.jfw.jina.util.concurrent.spi.NioAsyncExecutor;

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
		request.requestExecutor.begin(request, request.parameters);
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
		} else if (contentLength() >= 0) {
			nextState = State.READ_FIXED_LENGTH_CONTENT;
		} else {
			nextState = State.READ_VARIABLE_LENGTH_CONTENT;
		}
		this.request.doAfterHeaderParse();
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

	protected void createInvalidHttpresutst(HttpResponseStatus error){
		this.contentLength = Long.MAX_VALUE;
		this.currentState = State.READ_FIXED_LENGTH_CONTENT;

		
		OutputBuf buf = executor.alloc();
		
		byte[] bs = error.getReason();
		buf = this.writeAscii(buf, "HTTP/1.1 "+error.getCode()+' ');
		buf = this.writeBytes(buf, bs,0,bs.length);
		buf = this.writeBytes(buf, HttpConsts.CRLF, 0,HttpConsts.CRLF.length);
		
		
		
		
	}
	
	protected OutputBuf writeHttpHeader(OutputBuf buf,String name,String value){
		int sBegin =0;
		int sEnd = value.length();
		int lineIdx = name.length();
		buf=this.writeAscii(buf, name);
		buf=this.writeByte(buf, ':');
	    if(lineIdx+sEnd >= 1022){
	    	buf = this.writeAscii(buf, value);
	    	buf = this.writeBytes(buf,HttpConsts.CRLF,0,HttpConsts.CRLF.length);
	    }else{
	    	
	    }
		
		while(sBegin< sEnd){
			int n = 1023-lineIdx;
			
			buf = this.writeAscii(buf, value.substring(sBegin,sBegin+n));
			sBegin 
			
		}
		
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
					if(this.request!=null){
						
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
						request.requestExecutor.appendRequestBody(request, buf);
					}
					return;
				}
				case READ_FIXED_LENGTH_CONTENT: {
					if (chunkSize == 0) {
						this.request.requestExecutor.end(request, request.response, true);
						this.resetRequest();
					} else {
						long nr = buf.readableBytes();
						if (nr > chunkSize) {
							InputBuf dbuf = buf.duplicate((int) chunkSize);
							this.request.requestExecutor.appendRequestBody(request, dbuf);
							dbuf.release();
							buf.skipBytes((int) chunkSize);
							this.request.requestExecutor.end(request, request.response, true);
							this.resetRequest();
							this.currentState = State.SKIP_CONTROL_CHARS;
							this.cleanOpRead();
						} else {
							this.request.requestExecutor.appendRequestBody(request, buf);
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
							this.request.requestExecutor.end(request, request.response, true);
							this.resetRequest();
							currentState = State.SKIP_CONTROL_CHARS;
							this.cleanOpRead();
						} else {
							currentState = State.READ_CHUNKED_CONTENT;
						}
					} catch (Exception e) {
						this.request.requestExecutor.end(request, request.response, false);
						return;
					}
				case READ_CHUNKED_CONTENT: {
					assert chunkSize <= Integer.MAX_VALUE;
					long nr = buf.readableBytes();
					if (nr > chunkSize) {
						InputBuf dbuf = buf.duplicate((int) chunkSize);
						this.request.requestExecutor.appendRequestBody(request, dbuf);
						dbuf.release();
						buf.skipBytes((int) chunkSize);
						this.currentState = State.READ_CHUNK_DELIMITER;
					} else {
						this.request.requestExecutor.appendRequestBody(request, buf);
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
		case READ_INITIAL: {
		}

		case READ_HEADER: {
		}
		case READ_VARIABLE_LENGTH_CONTENT: {
			this.request.requestExecutor.end(request, request.response, true);
		}
		case READ_FIXED_LENGTH_CONTENT:
		case READ_CHUNK_SIZE:
		case READ_CHUNKED_CONTENT:
		case READ_CHUNK_DELIMITER: {
			this.request.requestExecutor.end(request, request.response, false);
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
		protected RequestExecutor requestExecutor;

		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		protected DefaultHttpParameters parameters = new DefaultHttpParameters();
		protected HttpServerResponse response;

		public HttpServerRequest() {
			this.response = new HttpServerResponse();
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
		public NioAsyncExecutor executor() {
			return executor;
		}

		@Override
		public HttpHeaders headers() {
			return this.headers();
		}

		@Override
		public HttpParameters parameters() {
			return this.parameters;
		}
	}

	protected class HttpServerResponse implements HttpResponse {
		protected DefaultHttpHeaders headers = new DefaultHttpHeaders();
		LinkedNode begin = executor.getNode();

		protected int state = STATE_INIT;
		protected boolean requestKeepAlive = true;
		protected HttpResponseStatus hrs = HttpResponseStatus.OK;

		@Override
		public void addHeader(String name, String value) {
			this.headers.add(name, value);
		}

		protected void sendResponseLineAndHeader() {
		}

		@Override
		public void addBody(InputBuf buf) {
			if (state == STATE_INIT) {
				this.sendResponseLineAndHeader();
				state = STATE_SENDING_DATA;
			} else if (state == STATE_SENDED) {
				throw new IllegalStateException();
			}
			if (buf.readable()) {
				write(buf, null);
			}
		}

		@Override
		public void flush(InputBuf buf, AsyncTask task) {
			write(EmptyBuf.INSTANCE, task);
		}

		@Override
		public void setStatus(HttpResponseStatus httpResponseStatus) {
			this.hrs = httpResponseStatus;
		}

		@Override
		public void fail() {
			close();
		}

		protected void flush(byte[] bs, AsyncTask task) {
			int len = bs.length;
			int idx = 0;
			while (len > 0) {
				OutputBuf buf = executor.alloc();
				int hlen = buf.writableBytes();
				if (hlen >= len) {
					buf.writeBytes(bs, idx, len);
					write(buf.input(), task);
					buf.release();
				} else {
					buf.writeBytes(bs, idx, hlen);
					write(buf.input());
					buf.release();
					idx += hlen;
					len -= hlen;
				}
			}
		}

		@Override
		public void sendClientError(HttpResponseStatus error) {
			boolean pkeepAlive = this.requestKeepAlive && error.isKeepAlive();
			
			
			if(state!= STATE_INIT){
				throw new IllegalStateException();
			}
			state =STATE_SENDED;
		 byte[] context = error.getDefautContent();
			int cl = context.length;
			this.hrs = error;
			this.headers.clear();
			if(!pkeepAlive)		this.headers.add(HttpConsts.CONNECTION, HttpConsts.CLOSE);
			this.headers.add(HttpConsts.CONTENT_LENGTH, Integer.toString(cl));
			this.headers.add(HttpConsts.CONTENT_TYPE, HttpConsts.TEXT_HTML_UTF8);
			this.sendResponseLineAndHeader();
			if(cl>0){
				this.flush(context, pkeepAlive?beginRead:closeTask);
			}else{
				write(EmptyBuf.INSTANCE, pkeepAlive?beginRead:closeTask);
			}
		}

		@Override
		public int state() {
			return this.state;
		}

	}

	public void keepAliveTimeout() {
		this.closeJavaChannel();
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
