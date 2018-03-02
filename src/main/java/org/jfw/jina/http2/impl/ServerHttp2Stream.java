package org.jfw.jina.http2.impl;

import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.server.HttpRequest;
import org.jfw.jina.http.server.HttpResponse;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Stream;
import org.jfw.jina.http2.impl.Http2FrameWriter.Frame;
import org.jfw.jina.util.DQueue.DNode;

public class ServerHttp2Stream implements Http2Stream, HttpRequest, HttpResponse {
	boolean suspendRead = false;
	HttpRequest.HttpMethod method;
	String path;
	String queryString;
	String hash;
	HttpHeaders headers;
	RequestExecutor requestExecutor;
	int initWindowSize;
	int recvWindowSize;
	int sendWindowSize;
	int id;
	byte state;
	
	protected int resState = HttpResponse.STATE_INIT;
	protected DefaultHttpHeaders resHeaders;
	protected HttpResponseStatus resStatus = HttpResponseStatus.OK;

	Frame first = null;
	Frame last = null;

	protected DNode nodeRef;

	protected final Http2ServerConnection<? extends Http2AsyncExecutor> connection;

	public ServerHttp2Stream(Http2ServerConnection<? extends Http2AsyncExecutor> connection, int windowSize) {
		this.connection = connection;
		this.initWindowSize = windowSize;
		this.resHeaders = new DefaultHttpHeaders();
	}

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
		int size = initWindowSize - recvWindowSize;

		if (size > 0) {
			connection.writeWindowUpdate(id, size);
			this.recvWindowSize = initWindowSize;
		}
	}

	@Override
	public void setRequestExecutor(RequestExecutor requestExecutor) {
		this.requestExecutor = requestExecutor;
	}

	@Override
	public void abort() {
		if (this.state == Http2Stream.STREAM_STATE_OPEN) {
			this.resState = HttpResponse.STATE_SENDED;
			connection.streamReset(this);
		}
	}



	@Override
	public int state() {
		return resState;
	}

	@Override
	public void addHeader(String name, String value) {
		this.resHeaders.add(name, value);
	}

	@Override
	public void setStatus(HttpResponseStatus httpResponseStatus) {
		resStatus = httpResponseStatus;

	}

	@Override
	public void write(byte[] buffer, int index, int length) {
		connection.streamWrite(this, buffer, index, length);
	}

	@Override
	public void flush(byte[] buffer, int index, int length, TaskCompletionHandler task) {
		connection.streamFlush(this, buffer, index, length, task);
	}

	@Override
	public void flush(TaskCompletionHandler task) {
		connection.streamFlush(this, task);
	}

	@Override
	public void flush() {
		connection.streamFlush(this);
	}

	@Override
	public void flush(byte[] buffer, int index, int length) {
		connection.streamFlush(this, buffer, index, length);
	}

	@Override
	public void unsafeContentLength(long length) {
		resHeaders.add(HttpConsts.CONTENT_LENGTH, Long.toString(length));
	}

	@Override
	public void unsafeWrite(byte[] buffer, int index, int length) {
		connection.streamWrite(this, buffer, index, length);
	}

	@Override
	public void unsafeWrite(byte[] buffer, int index, int length, TaskCompletionHandler task) {
		connection.streamWrite(this, buffer, index, length, task);

	}

	@Override
	public void unsafeFlush(byte[] buffer, int index, int length, TaskCompletionHandler task) {
		connection.streamFlush(this, buffer, index, length, task);
	}

	@Override
	public void unsafeFlush(byte[] buffer, int index, int length) {
		connection.streamFlush(this, buffer, index, length);

	}

	@Override
	public void unsafeFlush(TaskCompletionHandler task) {
		connection.streamFlush(this, task);
	}

	@Override
	public void unsafeFlush() {
		connection.streamFlush(this);
	}

	@Override
	public void fail() {
		if (this.resState != HttpResponse.STATE_SENDED) {
			this.resState = HttpResponse.STATE_SENDED;
			connection.streamReset(this);
		}
	}

	@Override
	public void sendClientError(HttpResponseStatus error) {
		if (this.resState != HttpResponse.STATE_INIT)
			throw new IllegalStateException();
		this.resStatus = error;
		byte[] content = error.getDefautContent();
		int cl = content.length;
		this.resHeaders.add(HttpConsts.CONTENT_TYPE, HttpConsts.TEXT_HTML_UTF8);
		this.unsafeContentLength(cl);
		this.unsafeFlush(content, 0, cl);
	}
	@Override
	public void changeInitialWindwSize(int size) {
	    int oldsize = this.sendWindowSize;  
		this.sendWindowSize+=size;
		if(this.sendWindowSize>oldsize && resState> HttpResponse.STATE_INIT){
			connection.streamWindowUpdateChange(this);
		}
	}

	@Override
	public void write(byte[] buffer, int index, int length, TaskCompletionHandler task) {
		connection.streamWrite(this, buffer, index, length, task);
	}

}
