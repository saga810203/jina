package org.jfw.jina.http.server;

import java.io.UnsupportedEncodingException;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.HttpConsts;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;

public class HttpService {
	public static final String CONTENT_TYPE_HTML = "text/html;charset=utf-8";

	private static final RequestExecutor builder = new RequestExecutor() {
		protected HttpAsyncExecutor executor;
		@Override
		public void requestBody(HttpRequest request, InputBuf buf) {
		}

		@Override
		public void execute(HttpRequest request, HttpResponse response) {
				response.addHeader(HttpConsts.TRANSFER_ENCODING, HttpConsts.CHUNKED);
				response.addHeader(HttpConsts.CONTENT_TYPE, CONTENT_TYPE_HTML);
				response.addHeader(HttpConsts.DATE,executor.dateFormatter.httpDateHeaderValue());
				OutputBuf buf =executor.allocBuffer();
				try {
					byte[] bs = null;
					try {
						bs = "2\r\nOK\r\n0\r\n".getBytes("UTF-8");
					} catch (UnsupportedEncodingException e) {
						// NO tring
					}
					response.addHeader(HttpConsts.CONTENT_LENGTH, Long.toString(bs.length));
					buf.writeBytes(bs);
					response.unsafeFlush(buf.input(),null);
				} finally {
					buf.release();
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
