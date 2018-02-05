package org.jfw.jina.ssl;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.NioAsyncChannel;
import org.jfw.jina.core.impl.NioAsyncExecutor;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.util.Handler;
import org.jfw.jina.util.Queue;

public class SslAsyncChannel implements NioAsyncChannel {
	static final int SSL_RECORD_HEADER_LENGTH = 5;

	private volatile long handshakeTimeoutMillis = 10000;
	private volatile long closeNotifyFlushTimeoutMillis = 3000;

	private boolean handshaked = false;

	private JdkSslEngine wrapSslEngine;
	
	
	public JdkSslEngine getWrapSslEngine() {
		return wrapSslEngine;
	}


	private NioAsyncChannel delegatedChannel;
	private final boolean isClient;
	private final Http2AsyncExecutor executor;
	private SocketChannel javaChannel;
	private SelectionKey key;

	private ByteArrayOutputStream cacheUnwrapData = new ByteArrayOutputStream();

	private Queue<ByteBuffer> outQueue;

	private ByteBuffer deCryptBuffer;

	public SslAsyncChannel(SslContext context, boolean isClient, Http2AsyncExecutor executor, SocketChannel javaChannel) {
		this.wrapSslEngine = context.newEngine();
		this.delegatedChannel = null;
		this.isClient = isClient;
		this.executor = executor;
		this.javaChannel = javaChannel;
		this.deCryptBuffer = executor.deCryptBuffer;
		this.readBuffer = ByteBuffer.allocate(8192);
		this.readIndex = this.writeIndex = 0;
		this.capacity = readBuffer.capacity();

	}

	public void doRegister() throws ClosedChannelException {
		assert this.javaChannel != null;
		this.key = this.javaChannel.register(this.executor.unwrappedSelector(), SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
		this.afterRegister();
	}

	private void afterRegister() {
		if (this.isClient) {
			handshake();
		} else {
			applyHandshakeTimeout();
		}

	}

	private void applyHandshakeTimeout() {
		this.executor.schedule(new AsyncTask() {
			@Override
			public void failed(Throwable exc, AsyncExecutor executor) {
			}

			@Override
			public void completed(AsyncExecutor executor) {
				// TODO Auto-generated method stub

			}

			@Override
			public void execute(AsyncExecutor executor) throws Throwable {
				if (handshaked) {
					return;
				} else {
					close();
				}
			}

			@Override
			public void cancled(AsyncExecutor executor) {
				close();
			}
		}, this.handshakeTimeoutMillis, TimeUnit.MILLISECONDS);

	}

	private void handshake() {
		// TODO Auto-generated method stub

	}


	private ByteBuffer readBuffer;

	private int capacity;
	private int readIndex;
	private int writeIndex;

	public int getReadIndex() {
		return readIndex;
	}

	public int getWriteIndex() {
		return writeIndex;
	}

	private void extendReadBuffer() {
		this.capacity += 4096;
		ByteBuffer buffer = ByteBuffer.allocate(capacity);
		buffer.put((ByteBuffer) readBuffer.flip().position(this.readIndex));
		this.writeIndex = this.writeIndex - this.readIndex;
		this.readIndex = 0;
		this.readBuffer = buffer;
	}

	private void compactReadBuffer() {
		this.readBuffer.flip();
		this.readBuffer.position(this.readIndex);
		this.readBuffer.compact();
		this.writeIndex = this.writeIndex - this.readIndex;
		this.readIndex = 0;
		this.readBuffer.limit(this.capacity).position(writeIndex);
	}

	@Override
	public void read() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.read();
			return;
		}
		int len = 0;
		for (;;) {
			try {
				len = this.javaChannel.read(readBuffer);
			} catch (Throwable e) {
				this.close();
				return;
			}
			if (len == 0) {
				break;
			} else if (len < 0) {
				this.close();
				return;
			}
			this.writeIndex += len;
			if (this.writeIndex >= capacity) {
				if (this.readIndex > 0) {
					this.compactReadBuffer();
				} else {
					this.extendReadBuffer();
				}
			}
		}
		//
		// int packetLength = this.packetLength;
		//
		// int readableBytes = this.rWriteIndex - this.rReadIndex;
		// if (readableBytes < SSL_RECORD_HEADER_LENGTH) {
		// return;
		// }
		// packetLength = SslUtil.getEncryptedPacketLength(readBuffer,
		// this.rReadIndex);
		// if (packetLength == SslUtil.NOT_ENCRYPTED) {
		// this.close();
		// return;
		// }
		//
		// if (packetLength > readableBytes) {
		// this.packetLength = packetLength;
		// return;
		// }
		// this.packetLength = 0;
		for (;;) {
			if (this.writeIndex - this.readIndex > 0) {
				if (!unwrap()) {
					return;
				}
				SSLEngineResult.HandshakeStatus state = this.wrapSslEngine.getHandshakeStatus();
				switch (state) {
					case FINISHED:
						this.handshaked = true;
						break;
					case NEED_TASK:
						for (;;) {
							Runnable task = wrapSslEngine.getDelegatedTask();
							if (task == null) {
								break;
							}
							task.run();
						}
						break;
					case NEED_UNWRAP:
						break;
					case NEED_WRAP:
						try {
							this.wrapNonAppData();
						} catch (SSLException e) {
							//TODO log  and exit;
							this.close();
						}
						return;
					case NOT_HANDSHAKING:
						if (!this.handshaked) {
							this.handshaked = true;
						}
						break;
					default:
						throw new IllegalStateException("Unknown handshake status: " + state);

				}
				if(handshaked){
					this.key.interestOps(0);
					this.swichHandle();
					return;
				}
			}
		}
	}
	
	public Queue<ByteBuffer> getOutCache(){
		return this.outQueue;
	}
	public ByteBuffer getReadBuffer(){
		return this.readBuffer;
	}
	public static
	
	private void swichHandle(){
		
	}

	private boolean unwrap() {
		ByteBuffer target = ByteBuffer.allocate(4096);
		ByteBuffer buf = (ByteBuffer) this.readBuffer.duplicate().flip().position(this.readIndex).limit(this.writeIndex);
		deCryptBuffer.clear();
		SSLEngineResult result;
		try {
			result = this.wrapSslEngine.unwrap(buf, deCryptBuffer);
		} catch (SSLException e) {
			this.close();
			return false;
		}
		int produced = result.bytesProduced();
		int consumed = result.bytesConsumed();
		this.readIndex += consumed;

		switch (result.getStatus()) {
			case BUFFER_OVERFLOW:
				// TODO log and gu exit IGNORE not happend
				throw new RuntimeException("decrypt buffer too small");
			case CLOSED:
				this.close();
				return false;
			default:
				break;
		}
		this.cacheUnwrapData(deCryptBuffer, produced);
		deCryptBuffer.clear();
		return true;
	}

//	private boolean unwrap(int length) throws SSLException {
//		final int originalLength = length;
//		boolean wrapLater = false;
//		boolean notifyClosure = false;
//		int overflowReadableBytes = -1;
//		ByteBuffer buffer = ByteBuffer.allocate(length);
//		try {
//			unwrapLoop: for (;;) {
//				final SSLEngineResult result = wrapSslEngine
//						.unwrap((ByteBuffer) this.readBuffer.duplicate().position(this.readIndex).limit(this.readIndex + length), buffer);
//				final Status status = result.getStatus();
//
//				final int produced = result.bytesProduced();
//				final int consumed = result.bytesConsumed();
//				this.readIndex += result.bytesConsumed();
//				length -= consumed;
//				switch (status) {
//					case BUFFER_OVERFLOW:
//						this.cacheUnwrapData(buffer, produced);
//						continue;
//					case CLOSED:
//						this.close();
//						return false;
//					default:
//						break;
//				}
//				final HandshakeStatus handshakeStatus = result.getHandshakeStatus();
//				switch (handshakeStatus) {
//					case NEED_UNWRAP:
//						break;
//					case NEED_WRAP:
//						// If the wrap operation transitions the status to
//						// NOT_HANDSHAKING and there is no more data to
//						// unwrap then the next call to unwrap will not produce
//						// any data. We can avoid the potentially
//						// costly unwrap operation and break out of the loop.
//						wrapNonAppData();
//						return;
//					case NEED_TASK:
//						runDelegatedTasks();
//						break;
//					case FINISHED:
//						setHandshakeSuccess();
//						wrapLater = true;
//
//						// We 'break' here and NOT 'continue' as android API
//						// version 21 has a bug where they consume
//						// data from the buffer but NOT correctly set the
//						// SSLEngineResult.bytesConsumed().
//						// Because of this it will raise an exception on the
//						// next iteration of the for loop on android
//						// API version 21. Just doing a break will work here as
//						// produced and consumed will both be 0
//						// and so we break out of the complete for (;;) loop and
//						// so call decode(...) again later on.
//						// On other platforms this will have no negative effect
//						// as we will just continue with the
//						// for (;;) loop if something was either consumed or
//						// produced.
//						//
//						// See:
//						// - https://github.com/netty/netty/issues/4116
//						// -
//						// https://code.google.com/p/android/issues/detail?id=198639&thanks=198639&ts=1452501203
//						break;
//					case NOT_HANDSHAKING:
//						if (setHandshakeSuccessIfStillHandshaking()) {
//							wrapLater = true;
//							continue;
//						}
//						if (flushedBeforeHandshake) {
//							// We need to call wrap(...) in case there was a
//							// flush done before the handshake completed.
//							//
//							// See https://github.com/netty/netty/pull/2437
//							flushedBeforeHandshake = false;
//							wrapLater = true;
//						}
//						// If we are not handshaking and there is no more data
//						// to unwrap then the next call to unwrap
//						// will not produce any data. We can avoid the
//						// potentially costly unwrap operation and break
//						// out of the loop.
//						if (length == 0) {
//							break unwrapLoop;
//						}
//						break;
//					default:
//						throw new IllegalStateException("unknown handshake status: " + handshakeStatus);
//				}
//
//				if (status == Status.BUFFER_UNDERFLOW || consumed == 0 && produced == 0) {
//					if (handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
//						// The underlying engine is starving so we need to feed
//						// it with more data.
//						// See https://github.com/netty/netty/pull/5039
//						readIfNeeded(ctx);
//					}
//
//					break;
//				}
//			}
//
//			if (wrapLater) {
//				wrap(ctx, true);
//			}
//
//			if (notifyClosure) {
//				notifyClosePromise(null);
//			}
//		} finally {
//			if (decodeOut != null) {
//				if (decodeOut.isReadable()) {
//					firedChannelRead = true;
//
//					ctx.fireChannelRead(decodeOut);
//				} else {
//					decodeOut.release();
//				}
//			}
//		}
//		return originalLength - length;
//	}

	private static ByteBuffer SSL_EMTPY_BUFFER = ByteBuffer.allocate(0);

	private void wrapNonAppData() throws SSLException {
		ByteBuffer buffer = null;
		int packLength = 2048;
		for (;;) {
			if (buffer == null) {
				buffer = ByteBuffer.allocate(2048);
				packLength = 2048;
			}
			SSLEngineResult result = this.wrapSslEngine.wrap(SSL_EMTPY_BUFFER, buffer);
			if (result.bytesProduced() > 0) {
				buffer.flip();
				this.outQueue.offer(buffer);
				buffer = null;
			}
			if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				packLength += 2048;
				buffer = ByteBuffer.allocate(packLength);
				continue;
			}
			if (result.bytesProduced() == 0) {
				return;
			}
		}
	}

	private void cacheUnwrapData(ByteBuffer buffer, int len) {
		byte[] bs = new byte[4096];
		buffer.flip();
		while (len > 0) {
			int nl = Integer.min(len, 4096);
			buffer.get(bs, 0, nl);
			this.cacheUnwrapData.write(bs, 0, nl);
			len -= nl;
		}
		buffer.clear();
	}

	@Override
	public void write() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.write();
			return;
		}
		ByteBuffer buffer = null;
		while ((buffer = this.outQueue.peek()) != null) {
			try {
				this.javaChannel.write(buffer);
			} catch (Throwable e) {
				this.close();
			}
			if (buffer.hasRemaining()) {
				return;
			}
			this.outQueue.unsafeShift();
		}
	}

	@Override
	public void setSelectionKey(SelectionKey key) {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.setSelectionKey(key);
			return;
		}

	}

	@Override
	public void close() {
		assert this.executor.inLoop();
		if (this.delegatedChannel != null) {
			this.delegatedChannel.close();
			return;
		}
		SocketChannel jc = this.javaChannel;
		SelectionKey k = this.key;
		this.javaChannel = null;
		this.key = null;
		if (k != null) {
			try {
				k.cancel();
			} catch (Exception e) {
			}
		}
		if (jc != null) {
			try {
				jc.close();
			} catch (Throwable t) {

			}
		}
		if (this.wrapSslEngine != null) {
			try {
				this.wrapSslEngine.closeInbound();
			} catch (Throwable e) {

			}
			try {
				this.wrapSslEngine.closeOutbound();
			} catch (Throwable e) {
			}
			this.wrapSslEngine = null;

		}
		this.readBuffer = null;
		if (this.outQueue != null) {
			this.outQueue.free(Handler.NOOP);
			this.outQueue = null;
		}

	}

	@Override
	public void connected() {
		if (this.delegatedChannel != null) {
			this.delegatedChannel.connected();
			return;
		}

	}

}
