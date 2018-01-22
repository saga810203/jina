package org.jfw.jina.http2;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static io.netty.handler.codec.http2.Http2Exception.streamError;

import java.nio.channels.SocketChannel;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.core.impl.NioAsyncChannel;
import org.jfw.jina.http.KeepAliveCheck;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.util.DQueue.DNode;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader.HeadersBlockBuilder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader.HeadersContinuation;

import org.jfw.jina.util.Queue;
import org.jfw.jina.util.impl.QueueProviderImpl.LinkedNode;
import org.jfw.jina.util.impl.QueueProviderImpl.LinkedQueue;

public abstract class Http2FrameChannel extends NioAsyncChannel<HttpAsyncExecutor> implements KeepAliveCheck {

	protected Http2FrameChannel(HttpAsyncExecutor executor, SocketChannel javaChannel) {
		super(executor, javaChannel);

		this.framePayload = executor.newQueue();
	}

	public static final int FRAME_CONFIG_HEADER_SIZE = 9;

	public static final int FRAME_PRIORITY_PAYLOAD_LENGTH = 5;
	public static final int FRAME_RSTSTREAM_PAYLOAD_LENGTH = 4;
	public static final int FRAME_SETTING_SETTING_ENTRY_LENGTH = 6;
	public static final int FRAME_PING_PAYLOAD_LENGTH = 8;

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

	protected int frameConfigMaxSize = 0x4000;

	protected byte[] frameHeaderBuffer = new byte[9];
	protected int frameHeaderIndex = 0;

	protected int payloadLength = 0;
	protected byte frameType = 0;
	protected byte frameFlag = 0;
	protected long streamId = 0;

	protected Queue framePayload;
	protected int payloadIndex = 0;

	protected boolean headerProcessing = false;
	protected long streamIdOfProcessingHeader = 0;

	private byte currentState = FRAME_STATE_READ_HEADER;

	protected void handleInputClose() {
		// TODO Auto-generated method stub

	}

	protected void handleProtocolError() {

	}

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
					if (!doReadFramePayLoad(buf)) {
						this.addKeepAliveCheck();
						return;
					}
				}
				if (this.currentState < 0) {
					this.handleProtocolError();
					return;
				}
			}

		} else {
			handleInputClose();
		}
	}

	private boolean doReadFramePayLoad(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(this.payloadLength - this.payloadIndex, nr);
		if (readSize > 0) {
			if (nr > readSize) {
				this.framePayload.offer(buf.duplicate(readSize));
				buf.skipBytes(readSize);
			} else {
				this.framePayload.offer(buf.slice());
				buf.skipAllBytes();
			}
			this.payloadIndex += readSize;
		}
		if (this.payloadIndex == this.payloadLength) {
			this.payloadIndex = 0;
			this.currentState = FRAME_STATE_READ_HEADER;

			if (frameType == FRAME_TYPE_DATA) {
				handleDataFrame();
			} else if (frameType == FRAME_TYPE_HEADERS) {
				handleHeadersFrame();
			} else if (frameType == FRAME_TYPE_PRIORITY) {
				handlePriorityFrame();
			} else if (frameType == FRAME_TYPE_RST_STREAM) {
				handleRstStreamFrame();
			} else if (frameType == FRAME_TYPE_SETTINGS) {
				handleSettingsFrame();
			} else if (frameType == FRAME_TYPE_PUSH_PROMISE) {
				handlePushPromiseFrame();
			} else if (frameType == FRAME_TYPE_PING) {
				handlePingFrame();
			} else if (frameType == FRAME_TYPE_GO_AWAY) {
				handleGoAwayFrame();
			} else if (frameType == FRAME_TYPE_WINDOW_UPDATE) {
				handleWindowUpdateFrame();
			} else if (frameType == FRAME_TYPE_CONTINUATION) {
				handleContinuationFrame();
			} else {
				handleUnknownFrame();
			}

			return true;
		} else {
			return false;
		}
	}

	protected void handleDataFrame() {

	}

	protected  void handleHeadersFrame(){
		   final int padding = readPadding();
		   if(padding!=0){
			   payloadLength -=(padding-1);
		   }
	        if (payloadLength < 0) {
	           this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
	           this.framePayload.clear(RELEASE_INPUT_BUF);
	           return;
	        }
		if(Http2FlagsUtil.priorityPresent(frameFlag)){
			this.readCacheBytes(4);
			this.payloadLength-=4;
			
			int tmpInt =  ((this.frameHeaderBuffer[0]     & 0xff) << 24) |
		                ((this.frameHeaderBuffer[2] & 0xff) << 16) |
		                ((this.frameHeaderBuffer[3] & 0xff) <<  8) |
		                		(this.frameHeaderBuffer[4] & 0xff);
			long word1 = tmpInt & 0xFFFFFFFFL;
			 final boolean exclusive = (word1 & 0x80000000L) != 0;
			 final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
			 if (streamDependency == streamId) {
				 //TODO impl			 
//	                throw streamError(streamId, PROTOCOL_ERROR, "A stream cannot depend on itself.");
	         }
			 final short weight = (short) (this.readUByteInPL() + 1);
			 --payloadLength;
			 this.changePayloadSize(payloadLength);
			 
		}
		
		
		//////////////////////
        if (flags.priorityPresent()) {
            long word1 = payload.readUnsignedInt();
            final boolean exclusive = (word1 & 0x80000000L) != 0;
            final int streamDependency = (int) (word1 & 0x7FFFFFFFL);
            if (streamDependency == streamId) {
                throw streamError(streamId, PROTOCOL_ERROR, "A stream cannot depend on itself.");
            }
            final short weight = (short) (payload.readUnsignedByte() + 1);
            final ByteBuf fragment = payload.readSlice(lengthWithoutTrailingPadding(payload.readableBytes(), padding));

            // Create a handler that invokes the listener when the header block is complete.
            headersContinuation = new HeadersContinuation() {
                @Override
                public int getStreamId() {
                    return headersStreamId;
                }

                @Override
                public void processFragment(boolean endOfHeaders, ByteBuf fragment,
                        Http2FrameListener listener) throws Http2Exception {
                    final HeadersBlockBuilder hdrBlockBuilder = headersBlockBuilder();
                    hdrBlockBuilder.addFragment(fragment, ctx.alloc(), endOfHeaders);
                    if (endOfHeaders) {
                        listener.onHeadersRead(ctx, headersStreamId, hdrBlockBuilder.headers(), streamDependency,
                                weight, exclusive, padding, headersFlags.endOfStream());
                    }
                }
            };

            // Process the initial fragment, invoking the listener's callback if end of headers.
            headersContinuation.processFragment(flags.endOfHeaders(), fragment, listener);
            resetHeadersContinuationIfEnd(flags.endOfHeaders());
            return;
	}

	protected abstract void handlePriorityFrame();

	protected abstract void handleRstStreamFrame();

	protected abstract void handleSettingsFrame();

	protected abstract void handlePushPromiseFrame();

	protected abstract void handlePingFrame();

	protected abstract void handleGoAwayFrame();

	protected abstract void handleWindowUpdateFrame();

	protected abstract void handleContinuationFrame();

	protected abstract void handleUnknownFrame();

	private boolean doReadFrameHeader(InputBuf buf) {
		int nr = buf.readableBytes();
		int readSize = Integer.min(FRAME_CONFIG_HEADER_SIZE - this.frameHeaderIndex, nr);
		buf.readBytes(this.frameHeaderBuffer, this.frameHeaderIndex, readSize);
		this.frameHeaderIndex += readSize;
		if (this.frameHeaderIndex == FRAME_CONFIG_HEADER_SIZE) {
			this.frameHeaderIndex = 0;

			this.payloadLength = (this.frameHeaderBuffer[0] & 0xff) << 16 | ((frameHeaderBuffer[1] & 0xff) << 8) | (frameHeaderBuffer[2] & 0xff);
			if (payloadLength > this.frameConfigMaxSize) {
				this.currentState = Http2ProtocolError.ERROR_MAX_FRAME_SIZE;
				return true;
			}
			frameType = this.frameHeaderBuffer[3];
			this.frameFlag = this.frameHeaderBuffer[4];
			streamId = ((frameHeaderBuffer[5] & 0x7f) << 24 | (frameHeaderBuffer[6] & 0xff) << 16 | (frameHeaderBuffer[7] & 0xff) << 8
					| frameHeaderBuffer[8] & 0xff);

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
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifyContinuationFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}

		if (!this.headerProcessing) {

			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION_NOT;
			return;
		}

		if (streamId != this.streamIdOfProcessingHeader) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_STREAM_ID_WITH_CONTINUATION;
			return;
		}

		if (payloadLength < Http2FlagsUtil.getPaddingPresenceFieldLength(frameFlag)) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}
	}

	private void verifyWindowUpdateFrame() {
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != 4) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE;
			return;
		}

	}

	private void verifyGoAwayFrame() {
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength < 8) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_GOAWAY;
			return;
		}

	}

	private void verifyPingFrame() {
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// IGNORE
		// }
		if (payloadLength != FRAME_PING_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PING;
			return;
		}

	}

	private void verifyPushPromiseFrame() {
		this.currentState = Http2ProtocolError.ERROR_NOT_SUPPORTED;
	}

	private void verifySettingsFrame() {
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		// if (streamId != 0) {
		// //IGNORE
		// }
		if (Http2FlagsUtil.ack(frameFlag) && payloadLength > 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING_ACK;
			return;
		}
		if (payloadLength % FRAME_SETTING_SETTING_ENTRY_LENGTH > 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING;
			return;
		}

	}

	private void verifyRstStreamFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_RSTSTREAM_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_RSTSTREAM;
			return;
		}
	}

	private void verifyPriorityFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}

		if (payloadLength != FRAME_PRIORITY_PAYLOAD_LENGTH) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PRIORITY;
			return;
		}
	}

	private void verifyHeadersFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < (Http2FlagsUtil.getNumPriorityBytes(this.frameFlag)) + Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	private void verifyDataFrame() {
		if (this.streamId == 0) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_NOT_ASSOCIATED_STREAM;
			return;
		}
		if (this.headerProcessing) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION;
			return;
		}
		if (payloadLength < Http2FlagsUtil.getPaddingPresenceFieldLength(this.frameFlag)) {
			this.currentState = Http2ProtocolError.ERROR_FRAME_INVALID_PAYLOAD_LENGTH;
			return;
		}

		// TODO AND EXISTS STREAM

	}

	// at first read in payload
	private int readPadding() {
		if (Http2FlagsUtil.paddingPresent(this.frameFlag)) {
			return readUByteInPL() + 1;
		} else {
			return 0;
		}
	}

	private short readUByteInPL() {
		assert !framePayload.isEmpty();
		InputBuf buf = (InputBuf) (((LinkedQueue) framePayload).head.next.item);
		assert buf.readable();
		short ret = buf.readUnsignedByte();
		if (!buf.readable()) {
			buf.release();
			framePayload.unsafeShift();
		}
		return ret;
	}

	private void readCacheBytes(int len) {
		assert len <= 9 && len > 0;
		assert this.frameHeaderIndex == 0;
		assert !framePayload.isEmpty();
		InputBuf buf = (InputBuf) framePayload.unsafePeek();
		assert buf != null;
		assert buf.readable();
		int ridx = 0;
		for (;;) {
			int rs = Integer.max(len, buf.readableBytes());
			buf.readBytes(this.frameHeaderBuffer, ridx, rs);
			len -= rs;
			if (len != 0) {
				framePayload.unsafeShift();
				ridx += rs;
				buf.release();
				buf = (InputBuf) framePayload.unsafePeek();
				assert buf != null;
				assert buf.readable();
			} else {
				break;
			}
		}
		if (!buf.readable()) {
			framePayload.unsafeShift();
		}
	}

	private void changePayloadSize(int len) {
		LinkedQueue queue = (LinkedQueue) this.framePayload;
		LinkedNode node = queue.head;
		LinkedNode first = node.next;
		LinkedNode end = null;
		InputBuf buf = (InputBuf) first.item;
		InputBuf nbuf = null;
		if (first == node.tag) {
			nbuf = buf.duplicate(len);
			// buf.skipAllBytes();
			buf.release();
			first.item = nbuf;
			return;
		}
		for (;;) {
			int rc = buf.readableBytes();
			if (rc > len) {
				nbuf = buf.duplicate(len);
				// buf.skipAllBytes();
				buf.release();
				first.item = nbuf;
				end = first.next;
				if (end == null) {
					return;
				}
				node.tag = first;
				first.next = null;
				break;
			} else if (rc == len) {
				end = first.next;
				node.tag = first;
				first.next = null;
				break;
			} else {
				first = first.next;
				buf = (InputBuf) first.item;
				len -= rc;
			}
		}
		assert end != null;
		first = end;
		int num = 0;
		for (;;) {
			node = end;
			buf = (InputBuf) end.item;
			buf.release();
			++num;
			node.item = null;
			end = node.next;
			if (end == null) {
				if (num == 1) {
					executor.freeDNode(first);
				} else {
					executor.freeNode(first, node, num);
				}
			}
		}
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
