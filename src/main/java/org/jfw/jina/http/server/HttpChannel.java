package org.jfw.jina.http.server;

import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.util.common.Queue;
import org.jfw.jina.util.common.Queue.Node;
import org.jfw.jina.util.concurrent.AsyncTask;
import org.jfw.jina.util.concurrent.NioAsyncChannel;
import org.jfw.jina.util.concurrent.spi.AbstractAsyncExecutor.LinkedNode;

import io.netty.handler.codec.http.HttpObjectDecoder.State;
import io.netty.util.internal.AppendableCharSequence;

public class HttpChannel extends NioAsyncChannel<HttpAsyncExecutor> {
	private enum State {
		SKIP_CONTROL_CHARS ,
		READ_INITIAL ,
		READ_HEADER ,
		READ_VARIABLE_LENGTH_CONTENT ,
		READ_FIXED_LENGTH_CONTENT ,
		READ_CHUNK_SIZE ,
		READ_CHUNKED_CONTENT ,
		READ_CHUNK_DELIMITER ,
		READ_CHUNK_FOOTER ,
		BAD_MESSAGE ,
		UPGRADED
	}

	private Node keepAliveNode;
	private Queue keepAliveQueue;
	private long keepAliveTimeout;

	protected HttpServerRequest request = null;

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

	protected boolean skipControlCharacters() {
		InputBuf buf = (InputBuf) inputHead.next.item;
		while (buf != null) {
			if (buf.skipControlCharacters()) {
				return true;
			} else {
				buf = removeFirstReadBuf();
			}
		}
		return false;
	}
	protected String readLine(){
		
	}

	private State currentState = State.SKIP_CONTROL_CHARS;

	@Override
	protected void handleRead() {
		switch (currentState) {
			case SKIP_CONTROL_CHARS: {
				if (!skipControlCharacters()) {
					return;
				}
				currentState = State.READ_INITIAL;
			}
			case READ_INITIAL:
				try {
					AppendableCharSequence line = lineParser.parse(buffer);
					if (line == null) {
						return;
					}
					String[] initialLine = splitInitialLine(line);
					if (initialLine.length < 3) {
						// Invalid initial line - ignore.
						currentState = State.SKIP_CONTROL_CHARS;
						return;
					}

					message = createMessage(initialLine);
					currentState = State.READ_HEADER;
					// fall-through
				} catch (Exception e) {
					out.add(invalidMessage(buffer, e));
					return;
				}
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
