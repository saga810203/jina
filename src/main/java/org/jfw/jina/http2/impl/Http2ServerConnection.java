package org.jfw.jina.http2.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SocketChannel;

import org.jfw.jina.core.TaskCompletionHandler;
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
import org.jfw.jina.util.DQueue;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.Matcher;
import org.jfw.jina.util.Queue;
import org.jfw.jina.util.StringUtil;

public class Http2ServerConnection<H extends Http2AsyncExecutor> extends Http2ConnectionImpl<ServerHttp2Stream, H> {

	protected HttpService service;

	protected int nextStreamId = 1;

	// protected int openedStreams = 0;
	// protected int supendedStreams = 0;
	protected ServerHttp2Stream lastCreatedStream = null;

	public Http2ServerConnection(H executor, SocketChannel javaChannel, Http2Settings settings) {
		super(executor, javaChannel, settings);

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
			stream.suspendRead = false;
		}
	}

	protected void requestBodyRead(final ServerHttp2Stream stream, int size, boolean endOfStream) {
		try {
			stream.requestExecutor.requestBody(stream, this.cachePayload.buffer, this.cachePayload.ridx, this.cachePayload.widx - this.cachePayload.ridx,
					endOfStream);
		} catch (Throwable e) {
			stream.requestExecutor = new InternalErrorRequestExecutor(e);
			stream.suspendRead = false;
		}
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
				removeStream(stream);
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
		assert stream.nodeRef != null;
		stream.nodeRef.dequeue();
		executor.freeDNode(stream.nodeRef);
		stream.nodeRef = null;
		freeStream(stream);
		--activeStreams;
		if (activeStreams == 0) {
			if (this.goAwayed) {
				this.writeCloseFrame();
			} else {
				this.addKeepAliveCheck();
			}
		}
	}

	public ServerHttp2Stream stream(final int id) {
		assert (id & 0x1) != 0;

		if (id == this.lastCreatedStream.id) {
			return this.lastCreatedStream;
		}
		int idx = (id >>> 1) & this.streamHashNum;
		return streams[idx].find(new Matcher<ServerHttp2Stream>() {
			@Override
			public boolean match(ServerHttp2Stream item) {
				return item.id == id;
			}
		});
	}

	@Override
	public void recvHeaders(HttpHeaders headers, boolean endOfStream) {
		if (goAwayed)
			return;

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

	void safeInvokeRequestError(ServerHttp2Stream stream, int errorCode) {
		try {
			stream.requestExecutor.error(stream, errorCode);
		} catch (Throwable e) {
		}
	}

	@Override
	public void resetStream(long error) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null) {
			if (stream.state == Http2Stream.STREAM_STATE_OPEN) {
				safeInvokeRequestError(stream, 484);
			}
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			streamCleanCacheData(stream);
			this.removeStream(stream);
		}
	}

	protected void streamCleanCacheData(ServerHttp2Stream stream) {
		while (stream.first != null) {
			TaskCompletionHandler lis = stream.first.listenner;
			if (lis != null) {
				executor.safeInvokeFailed(lis, this.lastWriteException);
			}
			stream.first = stream.first.next;
		}
		stream.last = null;
	}

	public void streamReset(ServerHttp2Stream stream) {
		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			this.writeRstStream(stream.id, Http2ProtocolError.INTERNAL_ERROR);
			streamCleanCacheData(stream);
			removeStream(stream);
		}
	}

	public void freeStream(ServerHttp2Stream stream) {
		stream.suspendRead = false;
		stream.method = null;
		stream.path = null;
		stream.queryString = null;
		stream.hash = null;
		stream.headers = null;
		stream.requestExecutor = null;
	}

	public Queue<ServerHttp2Stream> openedStream() {
		Queue<ServerHttp2Stream> list = executor.newQueue();
		for (int i = 0; i < this.streams.length; ++i) {
			this.streams[i].find(new Matcher<ServerHttp2Stream>() {
				@Override
				public boolean match(ServerHttp2Stream item) {
					if (item.state == Http2Stream.STREAM_STATE_OPEN)
						list.offer(item);
					return false;
				}
			});
		}
		return list;
	}

	@Override
	public void goAway(int lastStreamId, final long errorCode) {
		if (!goAwayed) {
			this.goAwayed = true;
		}
		Queue<ServerHttp2Stream> list = openedStream();
		list.find(new Matcher<ServerHttp2Stream>() {
			@Override
			public boolean match(ServerHttp2Stream item) {
				writeRstStream(item.id, errorCode);
				return false;
			}
		});
		list.free(new Handler<ServerHttp2Stream>() {

			@Override
			public void process(ServerHttp2Stream item) {
				item.state = Http2Stream.STREAM_STATE_CLOSED;
				streamCleanCacheData(item);
				removeStream(item);
			}
		});
	}

	// private void resetAllStream(final int errorCode) {
	// Queue<ServerHttp2Stream> list = executor.newQueue();
	// for (int i = 0; i < this.streams.length; ++i) {
	// this.streams[i].find(new Matcher<ServerHttp2Stream>() {
	// @Override
	// public boolean match(ServerHttp2Stream item) {
	// list.offer(item);
	// return false;
	// }
	// });
	// }
	// list.free(new Handler<ServerHttp2Stream>() {
	// @Override
	// public void process(ServerHttp2Stream item) {
	// writeRstStream(item.id, errorCode);
	// if (item.state == Http2Stream.STREAM_STATE_OPEN) {
	// safeInvokeRequestError(item, 484);
	// } else {
	//
	// }
	// removeStream(item);
	// }
	// });
	// }

	@Override
	public void streamWindowUpdate(int size) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null) {
			if (stream.sendWindowSize < 0) {
				stream.sendWindowSize += size;
			} else {
				stream.sendWindowSize += size;
				if (stream.sendWindowSize < 0) {
					stream.sendWindowSize = Integer.MAX_VALUE;
				}
			}
			if(stream.resState > HttpResponse.STATE_INIT){
				streamWindowUpdateChange(stream);
			}
		}
	}
	
	protected void streamWindowUpdateChange(ServerHttp2Stream stream){
		Frame frame = null;
		while (stream.first != null) {
			int nsw = stream.sendWindowSize - stream.first.length;
			if (nsw >= 0) {
				stream.sendWindowSize = nsw;
				frame = stream.first;
				stream.first = frame.next;
				frame.next = null;
				writeDataFrame(frame);
			} else {
				return;
			}
		}
		stream.last = null;
		if (stream.resState == HttpResponse.STATE_SENDED) {
			stream.state = Http2Stream.STREAM_STATE_CLOSED;
			removeStream(stream);
		}
	}
	



	@Override
	public void handleStreamData(int size, boolean endOfStream) {
		ServerHttp2Stream stream = stream(this.streamId);
		if (stream != null && stream.state == Http2Stream.STREAM_STATE_OPEN) {
			if (endOfStream) {
				stream.state = Http2Stream.STREAM_STATE_CLOSED_REMOTE;
			}
			this.requestBodyRead(stream, size, endOfStream);
		}
	}

	private void streamDataWrite(ServerHttp2Stream stream, Frame frame) {
		assert stream.state != Http2Stream.STREAM_STATE_CLOSED;
		assert frame != null;
		assert frame.next == null;
		if (null != stream.last) {
			stream.last.next = frame;
			stream.last = frame;
		} else {
			if (stream.sendWindowSize >= frame.length) {
				stream.sendWindowSize -= frame.length;
				writeDataFrame(frame);
			} else {
				stream.first = stream.last = frame;
			}
		}
	}

	private void streamLastDataWrite(ServerHttp2Stream stream, Frame frame) {
		assert stream.state != Http2Stream.STREAM_STATE_CLOSED;
		if (null != stream.last) {
			stream.last.next = frame;
			stream.last = frame;
		} else {
			if (stream.sendWindowSize >= frame.length) {
				stream.sendWindowSize -= frame.length;
				writeDataFrame(frame);
				stream.state = Http2Stream.STREAM_STATE_CLOSED;
				removeStream(stream);
			} else {
				stream.first = stream.last = frame;
			}
		}
	}

	void streamWrite(ServerHttp2Stream stream, byte[] buffer, int index, int length) {
		assert buffer != null;
		assert index >= 0;
		assert length > 0;
		assert buffer.length >= (index + length);
		assert stream != null;
		if (stream.state == Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				if (!writeHeaders(stream.id, stream.resState, stream.resHeaders, false)) {
					throw new IllegalArgumentException("response header encoder error");
				}
				stream.resState = HttpResponse.STATE_SENDING_DATA;
			} else if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			}
			Frame frame = buildDataFrame(stream.id, buffer, index, length, false);
			Frame f = null;
			while (frame != null) {
				f = frame.next;
				frame.next = null;
				this.streamDataWrite(stream, frame);
				frame = f;
			}
		}
	}

	void streamWrite(ServerHttp2Stream stream, byte[] buffer, int index, int length, TaskCompletionHandler task) {
		assert buffer != null;
		assert index >= 0;
		assert length > 0;
		assert buffer.length >= (index + length);
		assert stream != null;
		if (stream.state == Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				if (!writeHeaders(stream.id, stream.resState, stream.resHeaders, false)) {
					throw new IllegalArgumentException("response header encoder error");
				}
				stream.resState = HttpResponse.STATE_SENDING_DATA;
			} else if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			}
			Frame frame = buildDataFrame(stream.id, buffer, index, length, false);
			Frame tail = frame;
			while (tail.next != null) {
				frame = tail.next;
				tail.next = null;
				this.streamDataWrite(stream, tail);
				tail = frame;
			}
			tail.listenner = task;
			this.streamDataWrite(stream, tail);
		} else {
			executor.safeInvokeFailed(task, this.lastWriteException);
		}
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
				if (!writeHeaders(stream.id, stream.resState, stream.resHeaders, false)) {
					throw new IllegalArgumentException("response header encoder error");
				}
			} else if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			}
			stream.resState = HttpResponse.STATE_SENDED;
			Frame frame = buildDataFrame(stream.id, buffer, index, length, true);
			Frame tail = frame;
			while (tail.next != null) {
				frame = tail;
				tail = frame.next;
				frame.next = null;
				this.streamDataWrite(stream, frame);
			}
			tail.listenner = task;
			this.streamLastDataWrite(stream, tail);
		} else {
			executor.safeInvokeFailed(task, this.lastWriteException);
		}

	}

	void streamFlush(final ServerHttp2Stream stream, final TaskCompletionHandler task) {
		assert task != null;
		assert stream != null;
		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				if (writeHeaders(stream.id, stream.resState, stream.resHeaders, task)) {
					stream.resState = HttpResponse.STATE_SENDED;
					stream.state = Http2Stream.STREAM_STATE_CLOSED;
					removeStream(stream);
					return;
				}
				throw new IllegalArgumentException("response header encoder error");
			}
			if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			} else {
				stream.resState = HttpResponse.STATE_SENDED;
			}
			Frame frame = emptyDataFrame(stream.id);
			streamLastDataWrite(stream, frame);
		} else {
			executor.safeInvokeFailed(task, this.lastWriteException);
		}
	}

	void streamFlush(final ServerHttp2Stream stream) {
		assert stream != null;
		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				if (writeHeaders(stream.id, stream.resState, stream.resHeaders, true)) {
					stream.resState = HttpResponse.STATE_SENDED;
					stream.state = Http2Stream.STREAM_STATE_CLOSED;
					removeStream(stream);
					return;
				}
				throw new IllegalArgumentException("response header encoder error");
			} else if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			} else {
				stream.resState = HttpResponse.STATE_SENDED;
			}
			streamLastDataWrite(stream, emptyDataFrame(stream.id));
		}
	}

	void streamFlush(final ServerHttp2Stream stream, byte[] buffer, int index, int length) {
		assert buffer != null;
		assert index >= 0;
		assert length > 0;
		assert buffer.length >= (index + length);
		assert stream != null;

		if (stream.state != Http2Stream.STREAM_STATE_CLOSED) {
			if (stream.resState == HttpResponse.STATE_INIT) {
				if (!writeHeaders(stream.id, stream.resState, stream.resHeaders, false)) {
					throw new IllegalArgumentException("response header encoder error");
				}
			} else if (stream.resState == HttpResponse.STATE_SENDED) {
				throw new IllegalStateException();
			}
			stream.resState = HttpResponse.STATE_SENDED;
			Frame frame = buildDataFrame(stream.id, buffer, index, length, true);
			Frame tail = frame;
			while (tail.next != null) {
				frame = tail.next;
				tail.next = null;
				this.streamDataWrite(stream, tail);
				tail = frame;
			}
			this.streamLastDataWrite(stream, tail);
		}
	}

	@Override
	public void handlePriority(int streamDependency, short weight, boolean exclusive) {
	}

	@Override
	public void close() {
		this.removeKeepAliveCheck();
		this.executor.freeDNode(this.keepAliveNode);
		this.keepAliveNode = null;
		this.closeJavaChannel();
		this.closeWriter();
		DQueue<ServerHttp2Stream> list = executor.newDQueue();
		for (int i = 0; i < this.streams.length; ++i) {
			this.streams[i].offerToDQueue(list);
		}
		list.free(new Handler<ServerHttp2Stream>() {
			@Override
			public void process(ServerHttp2Stream item) {
				item.state = Http2Stream.STREAM_STATE_CLOSED;
				streamCleanCacheData(item);
				item.nodeRef = null;
				freeStream(item);
			}
		});
	}

	@Override
	public void connected() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void handleInputClose() {
		this.handleProtocolError();
	}

	@Override
	public void keepAliveTimeout() {
		this.removeKeepAliveCheck();
		if(!goAwayed){
			if(lastFrame==null && dataLast == null && activeStreams ==0){
				this.goAwayed = true;				
				this.writeGoAway(nextStreamId==1?0:(nextStreamId-2),0,executor.ouputCalcBuffer,0,0);
				this.writeCloseFrame();
			}else{
				this.addKeepAliveCheck();
				this.setOpRead();
			}
		}
	}

	@Override
	protected void handleProtocolError() {
		if (!goAwayed) {
			this.goAwayed = true;
		}
		Queue<ServerHttp2Stream> list = openedStream();
		list.find(new Matcher<ServerHttp2Stream>() {
			@Override
			public boolean match(ServerHttp2Stream item) {
				writeRstStream(item.id, Http2ProtocolError.PROTOCOL_ERROR);
				return false;
			}
		});
		this.writeGoAway(this.lastCreatedStream == null ? 0 : this.lastCreatedStream.id, 0, executor.ouputCalcBuffer, 0, 0);
		list.free(new Handler<ServerHttp2Stream>() {
			@Override
			public void process(ServerHttp2Stream item) {
				item.state = Http2Stream.STREAM_STATE_CLOSED;
				streamCleanCacheData(item);
				removeStream(item);
			}
		});
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
		public void requestBody(HttpRequest request, byte[] buffer, int i, int l, boolean end) {
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
