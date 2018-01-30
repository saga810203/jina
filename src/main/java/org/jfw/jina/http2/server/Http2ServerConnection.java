package org.jfw.jina.http2.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.core.impl.NioAsyncExecutor;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
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
import org.jfw.jina.util.Matcher;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.StringUtil;

public class Http2ServerConnection extends Http2ConnectionImpl<ServerHttp2Stream> {

	protected HttpService service;

	protected int nextStreamId = 1;

	protected int activeStreams = 0;
	protected int openedStreams = 0;
	protected int supendedStreams = 0;
	protected ServerHttp2Stream lastCreatedStream = null;

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

	protected void requestBodyRead(final ServerHttp2Stream stream, int size, boolean endOfStream) {
		this.dataPayload.clear(new Handler<InputBuf>() {
			@Override
			public void process(InputBuf obj) {
				try {
					stream.requestExecutor.requestBody(stream, obj);
				} catch (Throwable e) {
					stream.requestExecutor = new InternalErrorRequestExecutor(e);
					stream.suspendRead = false;
				} finally {
					obj.release();
				}
			}
		});
		if (endOfStream) {
			this.requestInvoke(stream);
		} else {
			if (!stream.suspendRead) {
				this.writeWindowUpdate(stream.id, size);
			}
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
		// if (this.streamId != this.nextStreamId) {
		// this.currentState = Http2ProtocolError.ERROR_INVALID_STREAM_ID;
		// return;
		// }
		// this.nextStreamId <<= 1;
		// if (this.activeStreams >= this.localMaxConcurrentStreams) {
		// this.writeRstStream(this.streamId, Http2StreamError.STREAM_REFUSED);
		// return;
		// }
		//
		// ServerHttp2Stream stream = currentStream = new
		// ServerHttp2Stream(this, this.localInitialWindowSize);
		// stream.id = this.streamId;
		// stream.state = endOfStream ? Http2Stream.STREAM_STATE_CLOSED_REMOTE :
		// Http2Stream.STREAM_STATE_OPEN;
		// stream.recvWindowSize = this.localInitialWindowSize;
		// stream.sendWindowSize = this.remoteInitialWindowSize;
		// stream.suspendRead = false;
		// if (!parseMethod(headers.get(HttpConsts.H2_METHOD))) {
		// return;
		// }
		// if (!parseRequestLine(headers.get(HttpConsts.H2_PATH))) {
		// return;
		// }
		// stream.headers = headers;
		// this.addStream(stream);
		// this.configRequest(stream);
		// if (endOfStream) {
		// this.requestInvoke(stream);
		// }
		this.recvHeaders(headers, endOfStream);
	}

	public static final int REQUEST_ERROR_METHOD = HttpResponseStatus.METHOD_NOT_ALLOWED.getCode();

	private boolean parseMethod(String m) {
		if (HttpConsts.GET.equals(m)) {
			lastCreatedStream.method = HttpRequest.HttpMethod.GET;
		} else if (HttpConsts.POST.equals(m)) {
			lastCreatedStream.method = HttpRequest.HttpMethod.POST;
		} else if (HttpConsts.DELETE.equals(m)) {
			lastCreatedStream.method = HttpRequest.HttpMethod.DELETE;
		} else if (HttpConsts.PUT.equals(m)) {
			lastCreatedStream.method = HttpRequest.HttpMethod.PUT;
		} else {
			// DefaultHttpHeaders rh = executor.shareHeaders;
			// assert rh.isEmpty();
			// rh.add(HttpConsts.DATE,
			// executor.dateFormatter.httpDateHeaderValue());
			// this.writeHeaders(this.streamId, REQUEST_ERROR_METHOD, rh, true);
			// rh.clear();
			this.writeRstStream(this.streamId, REQUEST_ERROR_METHOD);
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

				lastCreatedStream.path = StringUtil.normalize(seq, start, pEnd);
				if (qStart > 0) {
					++qStart;
					int qEnd = Integer.min(end, hStart);
					if (qStart < qEnd) {
						lastCreatedStream.queryString = new String(seq, qStart, qEnd - qStart);
					}
				}
				if (hStart < Integer.MAX_VALUE) {
					++hStart;
					if (hStart < end) {
						lastCreatedStream.hash = new String(seq, hStart, end - qStart);
					}
				}

				return true;
			}

		}
		// DefaultHttpHeaders rh = executor.shareHeaders;
		// assert rh.isEmpty();
		// rh.add(HttpConsts.DATE,
		// executor.dateFormatter.httpDateHeaderValue());
		// this.writeHeaders(this.streamId, REQUEST_BAD, rh, true);
		// rh.clear();
		this.writeRstStream(this.streamId, REQUEST_BAD);
		return false;
	}

	protected void addStream(ServerHttp2Stream stream) {
		assert (stream.id & 0x1) != 0;
		assert stream.state != Http2Stream.STREAM_STATE_CLOSED;
		int idx = (stream.id >>> 1) & this.streamHashNum;
		stream.nodeRef = streams[idx].offer(stream);
		++activeStreams;
	}

	protected void removeStream(ServerHttp2Stream stream) {
		if (stream.nodeRef != null) {
			stream.nodeRef.dequeue();
			executor.freeDNode(stream.nodeRef);
			stream.nodeRef = null;
			--activeStreams;
			if (activeStreams == 0) {
				if (this.goAwayed) {
					this.writeCloseFrame();
				} else {
					this.addKeepAliveCheck();
				}
			}
		}
	}

	public ServerHttp2Stream stream(final int id) {
		assert (id & 0x1) != 0;
		int idx = (id >>> 1) & this.streamHashNum;
		if (id == this.lastCreatedStream.id) {
			return this.lastCreatedStream;
		}
		return streams[idx].find(new Matcher<ServerHttp2Stream>() {
			@Override
			public boolean match(ServerHttp2Stream item) {
				return item.id == id;
			}
		});
	}

	@Override
	public void recvHeaders(HttpHeaders headers, boolean endOfStream) {
		if (this.streamId != this.nextStreamId) {
			this.currentState = Http2ProtocolError.ERROR_INVALID_STREAM_ID;
			return;
		}
		this.nextStreamId <<= 1;
		if (this.activeStreams >= this.localMaxConcurrentStreams) {
			this.writeRstStream(this.streamId, Http2StreamError.STREAM_REFUSED);
			return;
		}

		ServerHttp2Stream stream = lastCreatedStream = new ServerHttp2Stream(this, this.localInitialWindowSize);
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
			this.requestInvoke(stream);
		}
	}

	private void resetAllOpenedStream(final int errorCode) {
		Queue<ServerHttp2Stream> list = executor.newQueue();
		for (int i = 0; i < this.streams.length; ++i) {
			this.streams[i].find(new Matcher<ServerHttp2Stream>() {
				@Override
				public boolean match(ServerHttp2Stream item) {
					if (item.state == Http2Stream.STREAM_STATE_OPEN) {
						list.offer(item);
					}
					return false;
				}
			});
		}
		list.free(new Handler<ServerHttp2Stream>() {
			@Override
			public void process(ServerHttp2Stream item) {
				item.reset();
				removeStream(item);
				writeRstStream(item.id, errorCode);
			}
		});

	}

	@Override
	public void resetStream(long error) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null && stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			stream.reset();
			this.removeStream(stream);
		}
	}

	@Override
	public void goAway(int lastStreamId, long errorCode) {
		this.goAwayed = true;
		for (int i = lastStreamId + 2; i < this.nextStreamId; i += 2) {
			ServerHttp2Stream stream = stream(this.streamId);
			if (stream != null && stream.state != Http2Stream.STREAM_STATE_CLOSED) {
				stream.reset();
				this.removeStream(stream);
			}
		}
	}

	@Override
	public void streamWindowUpdate(int size) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null && stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			stream.windowUpdate(size);
		}
	}

	@Override
	public void handleStreamData(int size, boolean endOfStream) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null && stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			this.requestBodyRead(stream, size, endOfStream);
		}
	}

	private void streamWrite(ServerHttp2Stream stream, Frame frame) {
		if (null != stream.last) {
			stream.last.next = frame;
			stream.last = frame;
			return;
		}
		Frame f = frame;
		Frame n = null;
		while (f != null) {
			n = f.next;
			f.next = null;
			if (stream.sendWindowSize >= f.length) {
				stream.sendWindowSize -= f.length;
				writeDataFrame(f);
			} else {
				if (stream.last == null) {
					stream.first = stream.last = f;
				} else {
					stream.last.next = f;
					stream.last = f;
				}
			}
			f = n;
		}
	}

	void streamWrite(ServerHttp2Stream stream, byte[] buffer, int index, int length) {
		assert buffer != null;
		assert index >= 0;
		assert length >= 0;
		assert buffer.length >= (index + length);
		assert stream != null;

		if (length > 0 && stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				stream.resState = HttpResponse.STATE_SENDING_DATA;
				writeHeaders(stream.id, stream.resState, stream.resHeaders, false);
			}
			this.streamWrite(stream, buildDataFrame(stream.id, buffer, index, length, false));
		}
	}

	TaskCompletionHandler wrapStreamCloseListener(final ServerHttp2Stream stream, final TaskCompletionHandler task) {
		return new TaskCompletionHandler() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
				removeStream(stream);
				task.failed(exc, executor);
			}

			@Override
			public void completed(AsyncExecutor executor) {
				removeStream(stream);
				task.completed(executor);
			}
		};
	}

	TaskCompletionHandler wrapStreamCloseListener(final ServerHttp2Stream stream) {
		return new TaskCompletionHandler() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
				removeStream(stream);
			}

			@Override
			public void completed(AsyncExecutor executor) {
				removeStream(stream);
			}
		};
	}

	void streamFlush(final ServerHttp2Stream stream, byte[] buffer, int index, int length, final TaskCompletionHandler task) {
		assert buffer != null;
		assert index >= 0;
		assert length > 0;
		assert buffer.length >= (index + length);
		assert task != null;
		assert stream != null;

		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				writeHeaders(stream.id, stream.resState, stream.resHeaders, false);
			}
			stream.resState = HttpResponse.STATE_SENDED;
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			Frame head = buildDataFrame(stream.id, buffer, index, length, true);
			Frame tail = head;
			while (tail.next != null) {
				tail = tail.next;
			}
			tail.listenner =this.wrapStreamCloseListener(stream, task);
			this.streamWrite(stream, head);
		} else {
			NioAsyncExecutor.safeInvokeFailed(task, null, executor);
		}

	}
	void streamFlush(final ServerHttp2Stream stream, final TaskCompletionHandler task) {
		assert task != null;
		assert stream != null;

		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				writeHeaders(stream.id, stream.resState, stream.resHeaders, true);
			}
			stream.resState = HttpResponse.STATE_SENDED;
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			Frame head = new Frame();
			head.length=0;
			head.
			Frame tail = head;
			while (tail.next != null) {
				tail = tail.next;
			}
			tail.listenner =this.wrapStreamCloseListener(stream, task);
			this.streamWrite(stream, head);
		} else {
			NioAsyncExecutor.safeInvokeFailed(task, null, executor);
		}

	}	void streamFlush(final ServerHttp2Stream stream, byte[] buffer, int index, int length, final TaskCompletionHandler task) {
		assert buffer != null;
		assert index >= 0;
		assert length > 0;
		assert buffer.length >= (index + length);
		assert task != null;
		assert stream != null;

		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				writeHeaders(stream.id, stream.resState, stream.resHeaders, false);
			}
			stream.resState = HttpResponse.STATE_SENDED;
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			Frame head = buildDataFrame(stream.id, buffer, index, length, true);
			Frame tail = head;
			while (tail.next != null) {
				tail = tail.next;
			}
			tail.listenner =this.wrapStreamCloseListener(stream, task);
			this.streamWrite(stream, head);
		} else {
			NioAsyncExecutor.safeInvokeFailed(task, null, executor);
		}

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
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub

	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void handleInputClose() {
		this.resetAllOpenedStream(0);
		this.goAwayed = true;
		this.writeGoAway(this.lastCreatedStream.id, 0, executor.ouputCalcBuffer, 0, 0);
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
