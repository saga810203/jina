package org.jfw.jina.http.server;

import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.jfw.jina.buffer.ByteProcessor;
import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.exception.TooLongFrameException;
import org.jfw.jina.util.StringUtil;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.NioAsyncChannel;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.LinkedNode;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

public class HttpChannel extends NioAsyncChannel<HttpAsyncExecutor> {
	private enum State {
		SKIP_CONTROL_CHARS, READ_INITIAL, READ_HEADER, READ_VARIABLE_LENGTH_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, BAD_MESSAGE, UPGRADED
	}

	private Node keepAliveNode;
	private Queue keepAliveQueue;
	private long keepAliveTimeout;

	protected HttpServerRequest request = null;
	protected LineParser lineParser = new LineParser(8192);

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
		// TODO Auto-generated method stub

	}

	@Override
	public void connected() {
		// TODO Auto-generated method stub

	}

	protected HttpServerRequest createInvalidHttpLineRequest(Exception e) {
		this.currentState = State.READ_FIXED_LENGTH_CONTENT;
		this.contentLength = Long.MAX_VALUE;
		// TODO impl
		
		if(currentState == State.READ_INITIAL){
			if(e instanceof TooLongFrameException){
				
			}else{
				//error method
				//error uri
				//error version
				//....
			}
		}else if(currentState == State.READ_HEADER){
			if(e instanceof NumberFormatException){
				// error header name:content-length
			}
		}
		
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

	private State readHeaders(InputBuf buffer) {
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

		if (this.isTransferEncodingChunked()) {
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
			for (Map.Entry<String, String> entry : this.request.headers.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(HttpConsts.HEADER_NAME_CONTENT_LENGTH)) {
					String v = entry.getValue();
					if (v != null) {
						contentLength = Long.parseLong(v);
					}
				}
			}
		}
		return contentLength;
	}

	private boolean isTransferEncodingChunked() {
		for (Map.Entry<String, String> entry : this.request.headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(HttpConsts.HEADER_NAME_TRANSFER_ENCODING)
					&& HttpConsts.HEADER_VALUE_CHUNKED.equalsIgnoreCase(entry.getValue())) {
				return true;
			}
		}
		return false;
	}

	private State currentState = State.SKIP_CONTROL_CHARS;
	private long contentLength = 0;

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
						this.request = this.createInvalidHttpLineRequest(e);
						return;
					}

				case READ_HEADER:
					try {
						State nextState = readHeaders(buf);
						if (nextState == null) {
							return;
						}
						currentState = nextState;
						switch (nextState) {
						case SKIP_CONTROL_CHARS:
							// fast-path
							// No content is expected.
							out.add(message);
							out.add(LastHttpContent.EMPTY_LAST_CONTENT);
							resetNow();
							return;
						case READ_CHUNK_SIZE:
							if (!chunkedSupported) {
								throw new IllegalArgumentException("Chunked messages not supported");
							}
							// Chunked encoding - generate HttpMessage first.
							// HttpChunks will follow.
							out.add(message);
							return;
						default:
							/**
							 * <a href=
							 * "https://tools.ietf.org/html/rfc7230#section-3.3.3">
							 * RFC 7230, 3.3.3</a> states that if a request does
							 * not have either a transfer-encoding or a
							 * content-length header then the message body
							 * length is 0. However for a response the body
							 * length is the number of octets received prior to
							 * the server closing the connection. So we treat
							 * this as variable length chunked encoding.
							 */
							long contentLength = contentLength();
							if (contentLength == 0 || contentLength == -1 && isDecodingRequest()) {
								out.add(message);
								out.add(LastHttpContent.EMPTY_LAST_CONTENT);
								resetNow();
								return;
							}

							assert nextState == State.READ_FIXED_LENGTH_CONTENT
									|| nextState == State.READ_VARIABLE_LENGTH_CONTENT;

							out.add(message);

							if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
								// chunkSize will be decreased as the
								// READ_FIXED_LENGTH_CONTENT state reads data
								// chunk by chunk.
								chunkSize = contentLength;
							}

							// We return here, this forces decode to be called
							// again where we will decode the content
							return;
						}
					} catch (Exception e) {
						out.add(invalidMessage(buffer, e));
						return;
					}
				case READ_VARIABLE_LENGTH_CONTENT: {
					// Keep reading data as a chunk until the end of connection
					// is reached.
					int toRead = Math.min(buffer.readableBytes(), maxChunkSize);
					if (toRead > 0) {
						ByteBuf content = buffer.readRetainedSlice(toRead);
						out.add(new DefaultHttpContent(content));
					}
					return;
				}
				case READ_FIXED_LENGTH_CONTENT: {
					int readLimit = buffer.readableBytes();

					// Check if the buffer is readable first as we use the
					// readable byte count
					// to create the HttpChunk. This is needed as otherwise we
					// may end up with
					// create a HttpChunk instance that contains an empty buffer
					// and so is
					// handled like it is the last HttpChunk.
					//
					// See https://github.com/netty/netty/issues/433
					if (readLimit == 0) {
						return;
					}

					int toRead = Math.min(readLimit, maxChunkSize);
					if (toRead > chunkSize) {
						toRead = (int) chunkSize;
					}
					ByteBuf content = buffer.readRetainedSlice(toRead);
					chunkSize -= toRead;

					if (chunkSize == 0) {
						// Read all content.
						out.add(new DefaultLastHttpContent(content, validateHeaders));
						resetNow();
					} else {
						out.add(new DefaultHttpContent(content));
					}
					return;
				}
				/**
				 * everything else after this point takes care of reading
				 * chunked content. basically, read chunk size, read chunk, read
				 * and ignore the CRLF and repeat until 0
				 */
				case READ_CHUNK_SIZE:
					try {
						AppendableCharSequence line = lineParser.parse(buffer);
						if (line == null) {
							return;
						}
						int chunkSize = getChunkSize(line.toString());
						this.chunkSize = chunkSize;
						if (chunkSize == 0) {
							currentState = State.READ_CHUNK_FOOTER;
							return;
						}
						currentState = State.READ_CHUNKED_CONTENT;
						// fall-through
					} catch (Exception e) {
						out.add(invalidChunk(buffer, e));
						return;
					}
				case READ_CHUNKED_CONTENT: {
					assert chunkSize <= Integer.MAX_VALUE;
					int toRead = Math.min((int) chunkSize, maxChunkSize);
					toRead = Math.min(toRead, buffer.readableBytes());
					if (toRead == 0) {
						return;
					}
					HttpContent chunk = new DefaultHttpContent(buffer.readRetainedSlice(toRead));
					chunkSize -= toRead;

					out.add(chunk);

					if (chunkSize != 0) {
						return;
					}
					currentState = State.READ_CHUNK_DELIMITER;
					// fall-through
				}
				case READ_CHUNK_DELIMITER: {
					final int wIdx = buffer.writerIndex();
					int rIdx = buffer.readerIndex();
					while (wIdx > rIdx) {
						byte next = buffer.getByte(rIdx++);
						if (next == HttpConstants.LF) {
							currentState = State.READ_CHUNK_SIZE;
							break;
						}
					}
					buffer.readerIndex(rIdx);
					return;
				}
				case READ_CHUNK_FOOTER:
					try {
						LastHttpContent trailer = readTrailingHeaders(buffer);
						if (trailer == null) {
							return;
						}
						out.add(trailer);
						resetNow();
						return;
					} catch (Exception e) {
						out.add(invalidChunk(buffer, e));
						return;
					}
				case BAD_MESSAGE: {
					// Keep discarding until disconnection.
					buffer.skipBytes(buffer.readableBytes());
					break;
				}
				case UPGRADED: {
					int readableBytes = buffer.readableBytes();
					if (readableBytes > 0) {
						// Keep on consuming as otherwise we may trigger an
						// DecoderException,
						// other handler will replace this codec with the
						// upgraded protocol codec to
						// take the traffic over at some point then.
						// See https://github.com/netty/netty/issues/2173
						out.add(buffer.readBytes(readableBytes));
					}
					break;
				}
				}
			}

		}
	}

	private static final String[] END_HEADER_STRING_ARRAY = new String[] { StringUtil.EMPTY_STRING };

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
			cEnd = StringUtil.findEndOfString(seq, this.size);

			return new String[] { new String(seq, aStart, aEnd - aStart), new String(seq, bStart, bEnd - bStart),
					cStart < cEnd ? new String(seq, cStart, cEnd - cStart) : StringUtil.EMPTY_STRING };
		}

		public void reset() {
			size = 0;
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

		protected TooLongFrameException newException(int maxLength) {
			return new TooLongFrameException("HTTP header is larger than " + maxLength + " bytes.");
		}
	}

	protected class HttpServerRequest implements HttpRequest {
		protected HttpMethod method;
		protected String path;
		protected String queryString;
		protected String hash;

		protected Map<String, String> headers = new HashMap<String, String>();
		protected Map<String, String[]> parameters = new HashMap<String, String[]>();
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
		public String getHeader(String name) {
			return this.headers.get(name);
		}

		@Override
		public String getParameter(String name) {
			String[] ret = parameters.get(name);
			return (ret != null && ret.length > 0) ? ret[0] : null;
		}

		@Override
		public String[] getParameters(String name) {
			return parameters.get(name);
		}

		@Override
		public HttpResponse response() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void setBodyBuilder(HttpRequestBodyBuilder builder) {
			// TODO Auto-generated method stub

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

}
