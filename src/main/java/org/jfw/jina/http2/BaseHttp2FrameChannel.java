package org.jfw.jina.http2;

import java.nio.channels.SocketChannel;
import java.util.Comparator;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.TaskCompletionHandler;

public class BaseHttp2FrameChannel extends Http2FrameChannel {

	protected BaseHttp2FrameChannel(Http2AsyncExecutor executor, SocketChannel javaChannel) {
		super(executor, javaChannel);

	}

	protected void handlePingFrame() {
		if (Http2FlagsUtil.ack(frameFlag)) {
			//onPingAckRead();
		} else {
			OutputFrame frame = executor.outputFrame();
			this.readCacheBytes(8);
			frame.flag(Http2FlagsUtil.ack((byte) 0, true)).type(FRAME_TYPE_PING).write(this.frameHeaderBuffer, 0, 8);
			this.flushPingAck(frame);
		}
		assert this.framePayload.isEmpty();
	}

	private int numOfPingAck = 0;
	private int tmpForPingAck = 0;
	private Object firstOutputFrame = null;

	private Comparator<Object> pingAckFrameComparaotr = new Comparator<Object>() {
		@Override
		public int compare(Object o1, Object o2) {
			if(o1==firstOutputFrame)  return -1;			
			OutputFrame frame =(OutputFrame)o1;
			byte type = frame.type();
			byte flag = frame.flag();
			
			if(type ==FRAME_TYPE_CONTINUATION){
				return -1;
			}
			if(tmpForPingAck >0){
				if(type == FRAME_TYPE_PING && Http2FlagsUtil.ack(flag)){
					--tmpForPingAck;
				}
				return -1;
			}
			return 1;
		}

	};
	private TaskCompletionHandler flushPingAckListener = new TaskCompletionHandler() {
		@Override
		public void failed(Throwable exc, AsyncExecutor executor) {
		}

	@Override public void completed(AsyncExecutor executor){--numOfPingAck;}};

	private void flushPingAck(OutputFrame frame) {
		if (this.outputCache.isEmpty()) {
			this.outputCache.offer(frame, flushPingAckListener);
			return;
		} else {
			this.firstOutputFrame = this.outputCache.unsafePeek();
			this.tmpForPingAck = this.numOfPingAck;
			this.outputCache.beforeWith(frame, flushPingAckListener, pingAckFrameComparaotr);
			++numOfPingAck;
		}
		this.setOpWrite();
	}

	@Override
	public void close() {
	}

	@Override
	public void connected() {
		// TODO Auto-generated method stub

	}

	@Override
	public void onDataRead(boolean endOfStream) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onHeadersRead(boolean endOfStream) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onHeadersRead(int streamDependency, short weight, boolean exclusive, boolean endOfStream) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onPriorityRead(int streamDependency, short weight, boolean exclusive) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onRstStreamRead(long errorCode) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onSettingsAckRead() {
		// TODO Auto-generated method stub

	}

	@Override
	public void onSettingsRead(Http2Settings settings) {
		// TODO Auto-generated method stub

	}



	@Override
	public void onPushPromiseRead(int promisedStreamId) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onGoAwayRead(int lastStreamId, long errorCode) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onWindowUpdateRead(int windowSizeIncrement) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onUnknownFrame(byte frameType) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void handlePushPromiseFrame() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void handleUnknownFrame() {
		// TODO Auto-generated method stub

	}
}
