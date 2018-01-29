package org.jfw.jina.http2.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.http.server.HttpRequest;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;
import org.jfw.jina.http.server.HttpResponse;
import org.jfw.jina.http.server.HttpService;

import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.Http2Stream;
import org.jfw.jina.http2.Http2StreamError;
import org.jfw.jina.http2.impl.Http2ConnectionImpl;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.StringUtil;

public class Http2ServerConnection extends Http2ConnectionImpl {

	protected HttpService service;
	protected ServerHttp2Stream currentStream = null;

	public Http2ServerConnection(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key, Http2Settings settings) {
		super(executor, javaChannel, key, settings);

	}

	public void setService(HttpService service) {
		this.service = service;
	}

	protected void configRequest(ServerHttp2Stream stream) {
		try {
			service.service(stream);
			stream.requestExecutor.setAsyncExecutor(executor);
		} catch (Throwable e) {
			stream.requestExecutor = new InternalErrorRequestExecutor(e);
		}
	}

	protected void requestBodyRead(final ServerHttp2Stream stream, int size) {
		this.dataPayload.clear(new Handler<InputBuf>() {
			@Override
			public void process(InputBuf obj) {
				try {
					stream.requestExecutor.requestBody(stream, obj);
				} catch (Throwable e) {
					stream.requestExecutor = new InternalErrorRequestExecutor(e);
				} finally {
					obj.release();
				}
			}
		});
		if (!stream.suspendRead) {
			this.writeWindowUpdate(stream.id, size);
		}
	}

	protected void requestInvoke(final ServerHttp2Stream stream) {
		try {
			stream.requestExecutor.execute(stream, stream);
		} catch (Throwable e) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				stream.requestExecutor = new InternalErrorRequestExecutor(e);
				stream.requestExecutor.execute(stream, stream);
			} else {
				this.writeRstStream(stream.id, 500);
				stream.reset();
			}
		}
	}

	@Override
	public void recvHeaders(HttpHeaders headers, int streamDependency, short weight, boolean exclusive, boolean endOfStream) {
		if (this.streamId != this.nextStreamId) {
			this.currentState = Http2ProtocolError.ERROR_INVALID_STREAM_ID;
			return;
		}
		this.nextStreamId <<= 1;
		if (this.activeStreams >= this.localMaxConcurrentStreams) {
			this.writeRstStream(this.streamId, Http2StreamError.STREAM_REFUSED);
			return;
		}

		ServerHttp2Stream stream = currentStream = new ServerHttp2Stream();
		stream.id = this.streamId;
		stream.state = endOfStream ? Http2Stream.STREAM_STATE_CLOSED_REMOTE : Http2Stream.STREAM_STATE_OPEN;
		stream.recvWindowSize = this.localInitialWindowSize;
		stream.sendWindowSize = this.remoteInitialWindowSize;
		stream.suspendRead = false;
		if (!parseMethod(headers.get(HttpConsts.H2_METHOD))) {
			return;
		}
		if (!parseRequestLine(headers.get(HttpConsts.H2_PATH))) {
			return;
		}
		stream.headers = headers;
		this.addStream(stream);
		this.configRequest(stream);
		if (endOfStream) {

		}

	}

	public static final int REQUEST_ERROR_METHOD = HttpResponseStatus.METHOD_NOT_ALLOWED.getCode();

	private boolean parseMethod(String m) {
		if (HttpConsts.GET.equals(m)) {
			currentStream.method = HttpRequest.HttpMethod.GET;
		} else if (HttpConsts.POST.equals(m)) {
			currentStream.method = HttpRequest.HttpMethod.POST;
		} else if (HttpConsts.DELETE.equals(m)) {
			currentStream.method = HttpRequest.HttpMethod.DELETE;
		} else if (HttpConsts.PUT.equals(m)) {
			currentStream.method = HttpRequest.HttpMethod.PUT;
		} else {
			DefaultHttpHeaders rh = executor.shareHeaders;
			assert rh.isEmpty();
			rh.add(HttpConsts.DATE, executor.dateFormatter.httpDateHeaderValue());
			this.writeHeaders(this.streamId, REQUEST_ERROR_METHOD, rh, true);
			rh.clear();
			return false;
		}
		return true;
	}

	public static final int REQUEST_BAD = HttpResponseStatus.BAD_REQUEST.getCode();

	private boolean parseRequestLine(String m) {
		if (m != null && m.length() > 0) {
			char[] seq = m.toCharArray();
			int start = 0;
			int end = seq.length;

			int qStart = 0;
			int hStart = Integer.MAX_VALUE;
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

				currentStream.path = StringUtil.normalize(seq, start, pEnd);
				if (qStart > 0) {
					++qStart;
					int qEnd = Integer.min(end, hStart);
					if (qStart < qEnd) {
						currentStream.queryString = new String(seq, qStart, qEnd - qStart);
					}
				}
				if (hStart < Integer.MAX_VALUE) {
					++hStart;
					if (hStart < end) {
						currentStream.hash = new String(seq, hStart, end - qStart);
					}
				}

				return true;
			}

		}
		DefaultHttpHeaders rh = executor.shareHeaders;
		assert rh.isEmpty();
		rh.add(HttpConsts.DATE, executor.dateFormatter.httpDateHeaderValue());
		this.writeHeaders(this.streamId, REQUEST_BAD, rh, true);
		rh.clear();
		return false;
	}

	@Override
	public void recvHeaders(HttpHeaders headers, boolean endOfStream) {
		// TODO Auto-generated method stub

	}

	@Override
	public void resetStream(long error) {
		// TODO Auto-generated method stub

	}

	@Override
	public void goAway(int lastStreamId, long errorCode) {
		// TODO Auto-generated method stub

	}

	@Override
	public void streamWindowUpdate(int size) {
		// TODO Auto-generated method stub

	}

	@Override
	public void handleStreamData(int size, boolean endOfStream) {
		// TODO Auto-generated method stub

	}

	@Override
	public void handlePriority(int streamDependency, short weight, boolean exclusive) {
		// TODO Auto-generated method stub

	}

	@Override
	public void read() {
		// TODO Auto-generated method stub

	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		// TODO Auto-generated method stub

	}

	@Override
	public void close() {
		// TODO Auto-generated method stub

	}

	@Override
	public void connected() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void handleInputClose() {
		// TODO Auto-generated method stub

	}

	@Override
	public void keepAliveTimeout() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void handleProtocolError() {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isAppendToHeaderTable(String name, String value) {
		return false;
	}

	protected class ServerHttp2Stream extends Http2Stream implements HttpRequest, HttpResponse {
		protected boolean suspendRead = false;
		protected HttpRequest.HttpMethod method;
		protected String path;
		protected String queryString;
		protected String hash;
		protected HttpHeaders headers;
		protected RequestExecutor requestExecutor;

		@Override
		public HttpMethod method() {
			return method;
		}

		@Override
		public String path() {
			return path;
		}

		@Override
		public String queryString() {
			return queryString;
		}

		@Override
		public String hash() {
			return hash;
		}

		@Override
		public HttpHeaders headers() {
			return headers;
		}

		@Override
		public void suspendRead() {
			suspendRead = true;
		}

		@Override
		public void resumeRead() {
			if (localInitialWindowSize > recvWindowSize) {
				writeWindowUpdate(id, localInitialWindowSize - recvWindowSize);
				this.recvWindowSize = localInitialWindowSize;
			}
		}

		@Override
		public void setRequestExecutor(RequestExecutor requestExecutor) {
			this.requestExecutor = requestExecutor;
		}

		@Override
		public void abort() {
			// TODO Auto-generated method stub

		}

		protected int resState = HttpResponse.STATE_INIT;
		protected DefaultHttpHeaders resHeaders;
		protected HttpResponseStatus resStatus = HttpResponseStatus.OK;

		@Override
		public int state() {
			// TODO Auto-generated method stub
			return resState;
		}

		@Override
		public void addHeader(String name, String value) {
			// TODO Auto-generated method stub

		}

		@Override
		public void setStatus(HttpResponseStatus httpResponseStatus) {
			// TODO Auto-generated method stub

		}

		@Override
		public void write(byte[] buffer, int index, int length) {
			// TODO Auto-generated method stub

		}

		@Override
		public void flush(byte[] buffer, int index, int length, TaskCompletionHandler task) {
			// TODO Auto-generated method stub

		}

		@Override
		public void flush(TaskCompletionHandler task) {
			// TODO Auto-generated method stub

		}

		@Override
		public void flush() {
			// TODO Auto-generated method stub

		}

		@Override
		public void flush(byte[] buffer, int index, int length) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeContentLength(long length) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeWrite(byte[] buffer, int index, int length) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeWirte(InputBuf buffer, TaskCompletionHandler task) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeFlush(InputBuf buffer, TaskCompletionHandler task) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeFlush(InputBuf buffer) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeFlush(TaskCompletionHandler task) {
			// TODO Auto-generated method stub

		}

		@Override
		public void unsafeFlush() {
			// TODO Auto-generated method stub

		}

		@Override
		public void fail() {
			// TODO Auto-generated method stub

		}

		@Override
		public void sendClientError(HttpResponseStatus error) {
			// TODO Auto-generated method stub

		}

		public void reset(){}
	}

	public static class InternalErrorRequestExecutor implements RequestExecutor {
		private byte[] errorMessage = null;

		public InternalErrorRequestExecutor(Throwable e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			errorMessage = StringUtil.utf8(HttpResponseStatus.buildHtmlPage(HttpResponseStatus.INTERNAL_SERVER_ERROR, sw.toString()));
		}

		@Override
		public void setAsyncExecutor(HttpAsyncExecutor executor) {
		}

		@Override
		public void requestBody(HttpRequest request, InputBuf buf) {
		}

		@Override
		public void execute(HttpRequest request, HttpResponse response) {
			response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
			response.unsafeWrite(errorMessage, 0, errorMessage.length);
			response.unsafeFlush();
		}

		@Override
		public void error(HttpRequest request, int code) {
		}

		

	}
}
