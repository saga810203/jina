package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;

public class Http2ConnectionImpl extends Http2FrameWriter {
	
	private 
	
	
	
	 public Http2ConnectionImpl(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key,Http2Settings settings)  {
		super(executor,javaChannel,key);
		
		
	 }	

	@Override
	public void createStream(int streamDependency, short weight, boolean exclusive, boolean endOfStream) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void createStream(boolean endOfStream) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void resetStream(long error) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void applySetting(Http2Settings setting) {
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

	public class Stream{
		int id;
		
		
	} 

	
}
