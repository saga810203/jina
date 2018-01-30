package org.jfw.jina.http2.server;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.TaskCompletionHandler;
import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http.HttpResponseStatus;
import org.jfw.jina.http.impl.DefaultHttpHeaders;
import org.jfw.jina.http.server.HttpRequest;
import org.jfw.jina.http.server.HttpResponse;
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

	Frame first = null;
	Frame last = null;

	protected DNode nodeRef;

	protected final Http2ServerConnection connection;

	public ServerHttp2Stream(Http2ServerConnection connection, int windowSize) {
		this.connection = connection;
		this.initWindowSize = windowSize;
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
		// TODO Auto-generated method stub

	}

	protected int resState = HttpResponse.STATE_INIT;
	protected DefaultHttpHeaders resHeaders;
	protected HttpResponseStatus resStatus = HttpResponseStatus.OK;

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
		connection.streamFlush(this, buffer, index, length,task);
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

	public void reset() {
	}

	public void windowUpdate(int size) {
		int nsw = this.sendWindowSize + size;
		// IGNORE size error
		if (nsw < Integer.MAX_VALUE) {
			this.sendWindowSize = Integer.MAX_VALUE;
		} else {
			this.sendWindowSize = nsw;
		}
		Frame frame = null;
		while (first != null) {
			nsw = this.sendWindowSize - first.length;
			if (nsw >= 0) {
				frame = first;
				first = frame.next;
				frame.next = null;
				connection.writeDataFrame(frame);
				this.sendWindowSize = nsw;
			} else {
				return;
			}
		}
		if (first == null) {
			this.last = null;
		}
	}

	@Override
	public void changeInitialWindwSize(int size) {
		// TODO Auto-generated method stub

	}
}
