package org.jfw.jina.http.server;

import java.io.UnsupportedEncodingException;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.WritableHttpParameters;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;
import org.jfw.jina.util.concurrent.AsyncExecutor;
import org.jfw.jina.util.concurrent.AsyncTask;

public class HttpService {
	public static final String CONTENT_TYPE_HTML = "text/html;charset=utf-8";

	private static final RequestExecutor builder = new RequestExecutor() {
		protected HttpAsyncExecutor executor;
		@Override
		public void appendRequestBody(HttpRequest request, InputBuf buf) {
		}

		@Override
		public void end(HttpRequest request, HttpResponse response, boolean validBody) {
			if (validBody) {
				response.setStatus(200);
				response.addHeader(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
				response.addHeader(HttpConsts.CONTENT_TYPE, CONTENT_TYPE_HTML);
				response.addHeader(HttpConsts.DATE,executor.dateFormatter.httpDateHeader());
				OutputBuf buf = request.executor().alloc();
				try {
					byte[] bs = null;
					try {
						bs = "2\r\nOK\r\n0\r\n".getBytes("UTF-8");
					} catch (UnsupportedEncodingException e) {
						// NO tring
					}
					response.addHeader(HttpConsts.CONTENT_LENGTH, Long.toString(bs.length));
					buf.writeBytes(bs);
					response.addBody(buf.input());
				} finally {
					buf.release();
				}
				response.flush(new AsyncTask() {
					@Override
					public void failed(Throwable exc, AsyncExecutor executor) {
					}

					@Override
					public void execute(AsyncExecutor executor) throws Throwable {
					}

					@Override
					public void completed(AsyncExecutor executor) {

					}

					@Override
					public void cancled(AsyncExecutor executor) {
					}
				});
				buf.release();
			} else {
				// Clear resource
			}
		}

		@Override
		public void begin(HttpRequest request, WritableHttpParameters parameters) {
		}

		@Override
		public void setAsyncExecutor(HttpAsyncExecutor executor) {
			this.executor = executor;
		}
	};

	public void service(HttpRequest request) {
		request.setBodyBuilder(builder);
	}
}
