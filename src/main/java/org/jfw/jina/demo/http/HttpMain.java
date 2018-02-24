package org.jfw.jina.demo.http;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.impl.AbstractAsyncServerChannel;
import org.jfw.jina.core.impl.NioAsyncExecutorGroup;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.http.server.HttpChannel;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.ssl.SelfSignedCertificate;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.SslContext;
import org.jfw.jina.ssl.SslContextBuilder;

public class HttpMain {

	public static void main(String[] args) throws Throwable {
		final SelfSignedCertificate ssc = new SelfSignedCertificate();
		final SslContext sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).applicationProtocols("http/1.1") .build();
		NioAsyncExecutorGroup boss = new NioAsyncExecutorGroup(1);
		NioAsyncExecutorGroup worker = new NioAsyncExecutorGroup() {
			public AsyncExecutor newChild(Runnable closeTask) {
				return new Http2AsyncExecutor(this, closeTask, SelectorProvider.provider());
			}
		};

		AbstractAsyncServerChannel server = new AbstractAsyncServerChannel(boss, worker) {
			@Override
			public void accectClient(final SocketChannel channel, final AsyncExecutor executor) {
				executor.submit(new AsyncTask() {
					@Override
					public void completed(AsyncExecutor executor) {
					}

					@Override
					public void failed(Throwable exc, AsyncExecutor executor) {
						try {
							channel.close();
						} catch (Throwable t) {
						}
					}

					@Override
					public void execute(AsyncExecutor executor) throws Throwable {
						// HttpChannel<HttpAsyncExecutor> http = new
						// HttpChannel<HttpAsyncExecutor>((HttpAsyncExecutor
						// )executor, channel);
						// http.doRegister();

						
						SslAsyncChannel http = new SslAsyncChannel(sslContext, false, (Http2AsyncExecutor) executor, channel);
						http.doRegister();

					}

					@Override
					public void cancled(AsyncExecutor executor) {
						try {
							channel.close();
						} catch (Throwable t) {
						}

					}

				});
			}

		};

		server.start(new InetSocketAddress(91), 5);
	}

}
