package org.jfw.jina.demo.http;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.atomic.AtomicInteger;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.impl.AbstractAsyncServerChannel;
import org.jfw.jina.core.impl.NioAsyncExecutorGroup;
import org.jfw.jina.http.server.HttpService;
import org.jfw.jina.http2.Http2AsyncExecutor;
import org.jfw.jina.http2.Http2Settings;
import org.jfw.jina.ssl.SslAsyncChannel;
import org.jfw.jina.ssl.SslContext;
import org.jfw.jina.ssl.SslContextBuilder;

public class HttpMain {

	public static void main(String[] args) throws Throwable {
		//final SelfSignedCertificate ssc = new SelfSignedCertificate();
		final SslContext sslContext = SslContextBuilder.forServer(/*ssc.certificate()*/ new File("D:/ssl/crt.crt"),/* ssc.privateKey()*/new File("D:/ssl/key.key")).applicationProtocols("h2","http/1.1") .build();
		final Http2Settings http2Settings = new Http2Settings();
		http2Settings.headerTableSize(65536);
		http2Settings.pushEnabled(false);
		http2Settings.maxConcurrentStreams(1024);
		http2Settings.initialWindowSize(256*1024);
		http2Settings.maxFrameSize(16384);
		http2Settings.maxHeaderListSize(32*1024);
		final HttpService httpService = new HttpService();
		
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

						System.out.println(channel.getRemoteAddress());
						SslAsyncChannel http = new SslAsyncChannel(sslContext, false, (Http2AsyncExecutor) executor, channel);
						http.setService(httpService);
						http.setChannelId(getChannelId(channel));
						http.setLocalSetting(http2Settings);
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

			@Override
			public void setChannelId(String id) {
			}

		};
		server.start(new InetSocketAddress(443), 5);
	}

	private final static AtomicInteger num = new AtomicInteger(0);
	public static String getChannelId(SocketChannel channel){
		StringBuilder sb = new StringBuilder();
		sb.append("channel[id:").append(num.getAndIncrement()).append(",host:");
		try{
			InetSocketAddress isa =(InetSocketAddress)channel.getRemoteAddress();
			sb.append(isa.getHostName()).append(",port:").append(isa.getPort()).append("]");
		}catch(Throwable t){
			sb.append("invalid_host").append(",port:").append("invalid_port").append("]");
		}
		return sb.toString();
	}
}
