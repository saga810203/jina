package org.jfw.jina.http2.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.http.HttpHeaders;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.impl.Http2ConnectionImpl;

public class Http2ServerConnection extends Http2ConnectionImpl{

	public Http2ServerConnection(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key,
			Http2Settings settings) {
		super(executor, javaChannel, key, settings);
		
	}

	@Override
	public void recvHeaders(HttpHeaders headers, int streamDependency, short weight, boolean exclusive,
			boolean endOfStream) {
		// TODO Auto-generated method stub
		
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

}
