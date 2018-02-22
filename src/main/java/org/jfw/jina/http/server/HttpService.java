package org.jfw.jina.http.server;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.core.AsyncExecutor;
import org.jfw.jina.core.AsyncTask;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;

public class HttpService {
	public static final String CONTENT_TYPE_HTML = "text/html;charset=utf-8";
	private static final AtomicInteger refCount = new AtomicInteger();

	private static final RequestExecutor builder = new RequestExecutor() {
		protected HttpAsyncExecutor executor;

		@Override
		public void requestBody(HttpRequest request, byte[] buffer, int index, int length, boolean end) {
		}

		@Override
		public void execute(HttpRequest request, final HttpResponse response) {
			response.addHeader(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
			response.addHeader(HttpConsts.CONTENT_TYPE, CONTENT_TYPE_HTML);
			response.addHeader(HttpConsts.DATE, executor.dateFormatter.httpDateHeaderValue());

			byte[] bs = null;
			try {
				bs =(Long.toString(System.currentTimeMillis())+ "2\r\nOK\r\n0\r\n").getBytes("UTF-8");
			} catch (UnsupportedEncodingException e) {
				// NO tring
			}
			
			
			if(refCount.getAndIncrement() % 2 == 0){
				throw new RuntimeException();
			//response.flush(bs, 0, bs.length);
			}else{
				final byte[] aaa = bs;

				executor.schedule(new AsyncTask() {
					
					@Override
					public void failed(Throwable exc, AsyncExecutor executor) {
						// TODO Auto-generated method stub
						
					}
					
					@Override
					public void completed(AsyncExecutor executor) {
						// TODO Auto-generated method stub
						
					}
					
					@Override
					public void execute(AsyncExecutor executor) throws Throwable {
					response.flush(aaa,0,aaa.length);
						
					}
					
					@Override
					public void cancled(AsyncExecutor executor) {
						// TODO Auto-generated method stub
						
					}
				},5,TimeUnit.SECONDS);
			}

		}

		@Override
		public void setAsyncExecutor(HttpAsyncExecutor executor) {
			this.executor = executor;
		}

		@Override
		public void error(HttpRequest request, int code) {

		}
	};

	public void service(HttpRequest request) {
		request.setRequestExecutor(builder);
	}
}
