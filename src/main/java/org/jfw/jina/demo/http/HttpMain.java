package org.jfw.jina.demo.http;

import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashMap;
import java.util.Map;

import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.core.impl.AbstractAsyncServerChannel;
import org.jfw.jina.core.impl.NioAsyncExecutorGroup;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.http.server.HttpChannel;

public class HttpMain {

	public static void main(String[] args) throws Exception {
		NioAsyncExecutorGroup boss = new NioAsyncExecutorGroup(1);
		NioAsyncExecutorGroup worker = new NioAsyncExecutorGroup() {

			@Override
			protected Map<Object, Object> buildResources() {
				HashMap<Object, Object> ret = new HashMap<Object, Object>();
				return ret;
			}

			protected AsyncExecutor newChild(Runnable closeTask) {
				return new HttpAsyncExecutor(this, closeTask, SelectorProvider.provider(), this.buildResources());
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
						HttpChannel http = null;
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

		server.start(address, backlog);
	}

}
