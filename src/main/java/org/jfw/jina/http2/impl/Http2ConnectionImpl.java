package org.jfw.jina.http2.impl;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.http2.Http2Stream;
import org.jfw.jina.util.DQueue;

public abstract class Http2ConnectionImpl<T extends Http2Stream> extends Http2FrameWriter {

	protected DQueue<T>[] streams;

	protected int streamHashNum;


	@SuppressWarnings("unchecked")
	public Http2ConnectionImpl(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key, Http2Settings settings, int streamHashNum) {
		super(executor, javaChannel, key);
		assert streamHashNum == 1 || streamHashNum == 3 || streamHashNum == 7 || streamHashNum == 15 || streamHashNum == 31 || streamHashNum == 63|| streamHashNum == 127;
		this.streamHashNum = streamHashNum;
		this.streams = (DQueue<T>[]) new Object[streamHashNum + 1];
		for (int i = 0; i <= streamHashNum; ++i) {
			streams[i] = executor.<T> newDQueue();
		}
	}

	public Http2ConnectionImpl(Http2AsyncExecutor executor, SocketChannel javaChannel, SelectionKey key, Http2Settings settings) {
		this(executor, javaChannel, key, settings, 31);
	}


	public abstract T stream(int id);

	public void configLocal(Http2Settings settings) {
		Boolean b = settings.pushEnabled();
		if (b != null) {
			this.localEnablePush = b.booleanValue();
		}
		Long lv = settings.headerTableSize();
		;
		if (lv != null) {
			this.localHeaderTableSize = lv.longValue();
			this.localDynaTable.setCapacity(lv);
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
			if(this.maxHeaderListSize!=Long.MAX_VALUE){
				this.maxHeaderListSize = lv.longValue();
			}
		}

	}


	


}
