package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2ProtocolError;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.Http2Stream;
import org.jfw.jina.util.DQueue;
import org.jfw.jina.util.Matcher;

public class Http2ConnectionImpl<T extends Http2Stream> extends Http2FrameWriter {

	protected DQueue<Http2Stream>[] streams;

	protected int nextStreamId = 1;
	protected int streamHashNum;

	@SuppressWarnings("unchecked")
	public Http2ConnectionImpl(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key, Http2Settings settings, int streamHashNum) {
		super(executor, javaChannel, key);
		assert streamHashNum == 1 || streamHashNum == 3 || streamHashNum == 7 || streamHashNum == 15 || streamHashNum == 31 || streamHashNum == 63|| streamHashNum == 127;
		this.streamHashNum = streamHashNum;
		this.streams = (DQueue<Http2Stream>[]) new Object[streamHashNum + 1];
		for (int i = 0; i <= streamHashNum; ++i) {
			streams[i] = executor.<Http2Stream> newDQueue();
		}
	}

	public Http2ConnectionImpl(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key, Http2Settings settings) {
		this(executor, javaChannel, key, settings, 31);
	}

	protected void addStream(Http2Stream stream) {
		assert (stream.id & 0x1) != 0;
		int idx = (stream.id >>> 1) & this.streamHashNum;
		streams[idx].offer(stream);
	}

	protected Http2Stream stream(final int streamId) {
		assert (streamId & 0x1) != 0;
		int idx = (streamId >>> 1) & this.streamHashNum;
		return streams[idx].find(new Matcher<Http2Stream>() {
			@Override
			public boolean match(Http2Stream item) {
				return item.id == streamId;
			}
		});
	}

	public void configLocal(Http2Settings settings) {
		Boolean b = settings.pushEnabled();
		if (b != null) {
			this.localEnablePush = b.booleanValue();
		}
		Long lv = settings.headerTableSize();
		;
		if (lv != null) {
			this.localHeaderTableSize = lv.longValue();
		}
		lv = settings.maxConcurrentStreams();
		if (lv != null) {
			this.localMaxConcurrentStreams = lv.longValue();
		}
		Integer iv = settings.initialWindowSize();
		if (iv != null) {
			this.localInitialWindowSize = iv;
		}
		iv = settings.maxFrameSize();
		if (iv != null) {
			this.localMaxFrameSize = iv.intValue();
		}
		lv = settings.maxHeaderListSize();
		if (lv != null) {
			this.localMaxHeaderListSize = lv.longValue();
			this.localMaxHeaderListSizeGoAway = this.localMaxHeaderListSize +(this.localMaxHeaderListSize>>>2);
			if(this.localMaxHeaderListSizeGoAway < this.localMaxHeaderListSize){
				this.localMaxHeaderListSizeGoAway = Long.MAX_VALUE;
			}
		}
	}

	@Override
	public void createStream(int streamDependency, short weight, boolean exclusive, boolean endOfStream) {
		// TODO impl Priority
		this.createStream(endOfStream);
	}

	@Override
	public void createStream(boolean endOfStream) {
		if (this.streamId != nextStreamId) {
			this.currentState = Http2ProtocolError.ERROR_INVALID_STREAM_ID;
			return;
		}
		nextStreamId += 2;

	}

	@Override
	public void resetStream(long error) {
		// TODO Auto-generated method stub

	}

	@Override
	public void applySetting(Http2Settings settings) {
		// Boolean b = settings.pushEnabled();
		// if(b!=null){
		// this.localEnablePush = b.booleanValue();
		// }
		Long lv = settings.headerTableSize();
		if (lv != null) {
			this.remoteHeaderTableSize = lv.longValue();
			this.remoteDynaTable.setCapacity(lv.longValue());
		}
		lv = settings.maxConcurrentStreams();
		if (lv != null) {
			// TODO:
			this.remoteMaxConcurrentStreams = lv.longValue();
		}
		Integer iv = settings.initialWindowSize();
		if (iv != null) {
			// TODO:
			this.remoteInitialWindowSize = iv;
		}
		iv = settings.maxFrameSize();
		if (iv != null) {
			this.remoteMaxFrameSize = iv.intValue();
		}
		lv = settings.maxHeaderListSize();
		if (lv != null) {
			this.remoteMaxHeaderListSize = lv.longValue();
		}

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
