package org.jfw.jina.http2;

import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.impl.NioAsyncChannel;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.util.DQueue.DNode;

public abstract class Http2FrameChannel extends NioAsyncChannel<HttpAsyncExecutor> implements KeepAliveCheck {

	protected Http2FrameChannel(HttpAsyncExecutor executor, SocketChannel javaChannel) {
		super(executor, javaChannel);
		// TODO Auto-generated constructor stub
	}

	public static final int FRAME_CONFIG_HEADER_SIZE = 9;

	private static final byte FRAME_STATE_READ_HEADER = 10;
	private static final byte FRAME_STATE_READ_DATA = 20;

	public static final byte FRAME_TYPE_DATA = 0x0;
	public static final byte FRAME_TYPE_HEADERS = 0x1;
	public static final byte FRAME_TYPE_PRIORITY = 0x2;
	public static final byte FRAME_TYPE_RST_STREAM = 0x3;
	public static final byte FRAME_TYPE_SETTINGS = 0x4;
	public static final byte FRAME_TYPE_PUSH_PROMISE = 0x5;
	public static final byte FRAME_TYPE_PING = 0x6;
	public static final byte FRAME_TYPE_GO_AWAY = 0x7;
	public static final byte FRAME_TYPE_WINDOW_UPDATE = 0x8;
	public static final byte FRAME_TYPE_CONTINUATION = 0x9;

	public static final int DEFAULT_MAX_FRAME_SIZE = 0x4000;

	private int frameConfigMaxSize = 0x4000;

	private byte[] frameHeaderBuffer = new byte[9];
	private int frameHeaderIndex = 0;

	private int payloadLength = 0;
	private byte frameType = 0;
	private short frameFlag = 0;
	private long streamId = 0;

	private int framePayloadSize = 0;
	private int framePalyIndex = 0;

	private byte currentState = FRAME_STATE_READ_HEADER;

	@Override
	protected void handleRead(InputBuf buf, int len) {
		this.removeKeepAliveCheck();
		if (len > 0) {
			for (;;) {
				if (this.currentState == FRAME_STATE_READ_HEADER) {
					if (!doReadFrameHeader(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (this.currentState == FRAME_STATE_READ_DATA) {

				}

			}

		} else {
			handleInputClose();
		}
	}

	private boolean doReadFrameHeader(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		buf.readBytes(this.frameHeaderBuffer, this.frameHeaderIndex, readSize);
		this.frameHeaderIndex += readSize;
		if (this.frameHeaderIndex == FRAME_CONFIG_HEADER_SIZE) {
			this.frameHeaderIndex = 0;

			this.payloadLength = (this.frameHeaderBuffer[0] & 0xff) << 16 | ((frameHeaderBuffer[1] & 0xff) << 8)
					| (frameHeaderBuffer[2] & 0xff);
			if (payloadLength > this.frameConfigMaxSize) {
				this.currentState = Http2ProtocolError.ERROR_MAX_FRAME_SIZE;
				return true;
			}
			frameType = this.frameHeaderBuffer[3];
			this.frameFlag = (short) (this.frameHeaderBuffer[4] & 0xff);
			streamId = ((frameHeaderBuffer[5] & 0x7f) << 24 | (frameHeaderBuffer[6] & 0xff) << 16
					| (frameHeaderBuffer[7] & 0xff) << 8 | frameHeaderBuffer[8] & 0xff);

			this.currentState = FRAME_STATE_READ_DATA;
			if (frameType == FRAME_TYPE_DATA)
				verifyDataFrame();
			else if (frameType == FRAME_TYPE_HEADERS)
				verifyHeadersFrame();
			else if (frameType == FRAME_TYPE_PRIORITY)
				verifyPriorityFrame();
			else if (frameType == FRAME_TYPE_RST_STREAM)
				verifyRstStreamFrame();
			else if (frameType == FRAME_TYPE_SETTINGS)
				verifySettingsFrame();
			else if (frameType == FRAME_TYPE_PUSH_PROMISE)
				verifyPushPromiseFrame();
			else if (frameType == FRAME_TYPE_PING)
				verifyPingFrame();
			else if (frameType == FRAME_TYPE_GO_AWAY)
				verifyGoAwayFrame();
			else if (frameType == FRAME_TYPE_WINDOW_UPDATE)
				verifyWindowUpdateFrame();

			else if (frameType == FRAME_TYPE_CONTINUATION)
				verifyContinuationFrame();
			else
				verifyUnknownFrame();

			return true;
		}
		return false;

	}

	private void verifyUnknownFrame() {
		// TODO Auto-generated method stub
		

	}

	private void verifyContinuationFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyWindowUpdateFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyGoAwayFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyPingFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyPushPromiseFrame() {
		// TODO Auto-generated method stub

	}

	private void verifySettingsFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyRstStreamFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyPriorityFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyHeadersFrame() {
		// TODO Auto-generated method stub

	}

	private void verifyDataFrame() {
		// TODO Auto-generated method stub

	}

	

	private void handleInputClose() {
		// TODO Auto-generated method stub

	}

	@Override
	protected void afterRegister() {
		this.keepAliveNode = this.executor.newDNode(this);
		this.addKeepAliveCheck();
	}

	private DNode keepAliveNode;
	private long keepAliveTimeout = Long.MAX_VALUE;

	public boolean removeKeepAliveCheck() {
		if (this.keepAliveTimeout != Long.MAX_VALUE) {
			this.keepAliveTimeout = Long.MAX_VALUE;
			this.keepAliveNode.dequeue();
			return true;
		}
		return false;
	}

	public boolean addKeepAliveCheck() {
		if (this.keepAliveTimeout == Long.MAX_VALUE) {
			this.keepAliveTimeout = System.currentTimeMillis();
			this.keepAliveNode.enqueue(this.executor.getKeepAliveQueue());
			return true;
		}
		return false;
	}

	@Override
	public long getKeepAliveTime() {
		return this.keepAliveTimeout;
	}

	@Override
	public void keepAliveTimeout() {

		// TODO impl
		this.close();
	}

}
