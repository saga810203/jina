package org.jfw.jina.demo.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.jfw.jina.buffer.InputBuf;
import org.jfw.jina.buffer.OutputBuf;
import org.jfw.jina.http.server.HttpAsyncExecutor;
import org.jfw.jina.http.server.HttpRequest;
import org.jfw.jina.http.server.HttpRequest.HttpMethod;
import org.jfw.jina.http.server.HttpRequest.RequestExecutor;
import org.jfw.jina.http.server.HttpResponse;
import org.jfw.jina.http.server.HttpService;

public class TestMain {
	public static void main(String[] args) throws IOException, InterruptedException {
		/*
		 * StringBuilder sb= new StringBuilder(); sb.append(
		 * "POST /portal/ajax/leavemsg"); // for(int i = 0 ;i < 2;++i ){ //
		 * sb.append("/A"); // } sb.append(" HTTP/1.1\\r\n");
		 * sb.append(HttpConsts.TRANSFER_ENCODING).append(":").append(HttpConsts
		 * .CHUNKED).append("\r\n\r\n"); for(int i = 0 ;i < 8192;++i ){
		 * sb.append("AAAAA"); } testSocket(sb.toString());
		 */

		// int min = 500;
		// int max = 0;
		// int cnt = min;
		// int rmax = min;
		// Map<Object,Integer> map =new HashMap<>();
		// for(int i = 0 ; i <cnt; ++i){
		// int v = randomInt(rmax);
		// min = Integer.min(min,v);
		// max = Integer.max(max,v);
		// countTime(map,Integer.valueOf(v));
		// }
		//
		// System.out.println("min:"+min);
		// System.out.println("max:"+max);
		// writeMap(map);

		// System.out.println(HttpRequest.HttpMethod.GET.toString());
		// String c = "GET /test.html HTTP/1.1\r\n" + "Accept
		// :text/html,application/xhtml+xm…plication/xml;q=0.9,*/*;q=0.8\r\n"
		// + "Accept-Encoding:gzip, deflate\r\n" +
		// "Accept-Language:zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2\r\n"
		// + "Cache-Control:max-age=0\r\n" + "Host:192.168.3.233\r\n" +
		// "User-Agent:Mozilla/5.0 (Windows NT 6.1; W…) Gecko/20100101
		// Firefox/57.0\r\n"
		// + "\r\n";
		// testSocket(c);
		// ExecThread t = new ExecThread();
		// t.start();
		// Thread.sleep(10000);

	}

	public static class ExecThread extends Thread {
		private int v = 1243;

		@Override
		public void run() {
			Thread thread = Thread.currentThread();
			System.out.println(thread.getClass().getName());
		}

	}

	public static void countTime(Map<Object, Integer> map, Object key) {
		Integer v = map.get(key);
		if (v == null) {
			map.put(key, 1);
		} else {
			map.put(key, v + 1);
		}
	}

	public static void writeMap(Map<Object, Integer> map) {
		for (Map.Entry<Object, Integer> entry : map.entrySet()) {
			System.out.println(entry.getKey() + ":" + entry.getValue());
		}
	}

	public static void testSocket(String s) throws IOException, InterruptedException {
		long startTime = System.currentTimeMillis();
		byte[] buf = s.getBytes("UTF-8");
		Socket socket = new Socket("192.168.3.233", 8080);
		try {
			final CountDownLatch lock = new CountDownLatch(1);
			int idx = 1;
			OutputStream out = socket.getOutputStream();
			out.write(buf[0]);
			final InputStream in = socket.getInputStream();
			(new Thread() {

				@Override
				public void run() {
					// byte[] buffer = new byte[4096];
					for (;;) {
						int i = 0;
						try {
							i = in.read();
						} catch (IOException e) {
							System.out.println("");
							System.out.println("===============================");
							e.printStackTrace();
							System.out.println("===============================");
							break;
						}
						if (i < 0) {
							System.out.print("success close" + (System.currentTimeMillis() - startTime));
							break;
						}
						System.out.print((char) i);
					}
					lock.countDown();
				}

			}).start();

			while (idx < buf.length) {
				out.write(buf[idx++]);
			}
			out.flush();
			// socket.shutdownOutput();
			lock.await();
		} finally {
			socket.close();
		}
	}

	private static final char[] IC;
	private static final Random random = new Random();

	private static int randomInt(int max) {
		return random.nextInt(max);
	}

	private static String randomString(int max) {
		int count = Integer.max(1, randomInt(max));
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; ++i) {
			sb.append(rc());
		}
		return sb.toString();
	}

	private static boolean randomBoolean() {
		return random.nextInt(2) == 0;
	}

	private static char rc() {
		return IC[random.nextInt(IC.length)];
	}

	private static Map<String, String> randomHeader(int num) {
		Map<String, String> headers = new HashMap<String, String>();
		int count = Integer.max(1, randomInt(num));
		for (int i = 0; i < count; ++i) {
			String k = "JINA-" + randomString(30);
			String v = randomString(500);
			headers.put(k, v);
		}
		return headers;
	}

	static HttpRequest.HttpMethod randomHttpMethod() {
		int i = randomInt(4);
		if (i == 0)
			return HttpRequest.HttpMethod.GET;
		else if (i == 1)
			return HttpRequest.HttpMethod.POST;
		else if (i == 2) {
			return HttpRequest.HttpMethod.PUT;
		}
		return HttpRequest.HttpMethod.DELETE;
	}

	static TestHttpRequest buildTestHttpRequest() {
		TestHttpRequest ret = new TestHttpRequest();
		ret.method = randomHttpMethod();
		ret.path = randomString(500);
		ret.queryString = randomString(500);
		ret.hash = randomString(500);
		ret.headers = randomHeader(10);
		ret.close = randomBoolean();
		ret.chunked = randomBoolean();
		ret.facetor = randomInt(10000);
		ret.headers.put("X-N-T-" + "M", ret.method.toString());
		ret.headers.put("X-N-T-" + "P", ret.path);
		ret.headers.put("X-N-T-" + "Q", ret.queryString);
		ret.headers.put("X-N-T-" + "H", ret.hash);
		ret.headers.put("JINA_FACETOR", Integer.toString(ret.facetor));
		if (ret.method == HttpMethod.POST || ret.method == HttpMethod.PUT) {
			ret.body = randomString(randomInt(16 * 1024));
		} else {
			ret.body = null;
		}

		return ret;
	}

	static class TestHttpRequest {
		HttpRequest.HttpMethod method;
		String path;
		String queryString;
		String hash;
		Map<String, String> headers;
		String body;
		boolean close;
		boolean chunked;
		int facetor;
	}

	static boolean httpQuery(TestHttpRequest req) throws Exception {
		URL url = new URL("HTTP://192.168.3.20:8080/" + req.path);
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod(req.method.toString());
		con.setUseCaches(false);
		for (Map.Entry<String, String> entry : req.headers.entrySet()) {
			String n = entry.getKey();
			String v = entry.getValue();
			if (!n.startsWith("X-N-T-")) {
				con.setRequestProperty(n, v);
			}
		}
		con.setDoOutput(true);
		if (req.body != null) {
			byte[] bs = req.body.getBytes("UTF-8");
			if (!req.chunked) {
				con.setFixedLengthStreamingMode(bs.length);
			}
			OutputStream out = con.getOutputStream();
			try {
				out.write(bs);
				out.flush();
			} finally {
				out.close();
			}
		} else {
			con.setDoInput(false);
		}
		int code = con.getResponseCode();
		if (code != 200) {
			throw new Exception(" invalid response status code: " + code);
		}
		Map<String, String> resheaders = new HashMap<String, String>();
		for (Map.Entry<String, List<String>> entry : con.getRequestProperties().entrySet()) {
			String n = entry.getKey();
			String v = entry.getValue().get(1);
			resheaders.put(n, v);
		}
		String s = resheaders.get("JINA_FACETOR");
		if (s == null)
			throw new Exception("nofound response header:JINA_FACETOR");
		int f = 0;
		try {
			f = Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new Exception("invald response header[JINA_FACETOR] value:" + s);
		}

		ByteArrayOutputStream resout = new ByteArrayOutputStream();
		InputStream in = con.getInputStream();
		try {
			byte[] buf = new byte[4];
			int len = 0;
			while ((len = in.read(buf)) != -1) {
				if (len > 0) {
					resout.write(buf, 0, len);
				}
			}
		} finally {
			in.close();
		}

		String resbody = new String(resout.toByteArray(), "UTF-8");
		return resbody.equals(calc(req.body, f));

	}

	public static String calc(String src, int factor) {
		if (src == null || src.length() == 0) {
			return "12334t54gv5sdv46e8r4vropjOSIvh[ n74573rl;akad-0m23i ucf4ht/LA:cm q54uq2";
		}
		return src;
	}

	public static HttpService service = new HttpService() {
		@Override
		public void service(HttpRequest request) {
			request.setRequestExecutor(new RequestExecutor() {
				protected ByteArrayOutputStream out = new ByteArrayOutputStream();
				HttpAsyncExecutor executor;

				public void setAsyncExecutor(HttpAsyncExecutor executor) {
					this.executor = executor;
				}

				// public void requestBody(HttpRequest request, final InputBuf
				// buf) {
				// while (buf.readable()) {
				// out.write(buf.readByte() & 0xFF);
				// }
				// }

				@Override
				public void requestBody(HttpRequest request, byte[] buffer, int index, int length, boolean end) {
					// TODO Auto-generated method stub

				}

				public void execute(HttpRequest request, HttpResponse response) {
					int f = 1;
					for (Map.Entry<String, String> entry : request.headers()) {
						String n = entry.getKey();
						String k = entry.getValue();
						if (n.startsWith("JINA-")) {
							response.addHeader(n, k);
						} else {
							if (n.equals("JINA_FACETOR")) {
								try {
									f = Integer.parseInt(k);
								} catch (Throwable th) {
								}
							}
						}
					}
					response.addHeader("X-N-T-" + "M", request.method().toString());
					response.addHeader("X-N-T-" + "P", request.path());
					response.addHeader("X-N-T-" + "Q", request.queryString());
					response.addHeader("X-N-T-" + "H", request.hash());
					try {
						String r = new String(out.toByteArray(), "UTF-8");
						byte[] bs = calc(r, f).getBytes("UTF-8");
						response.flush(bs, 0, bs.length);
					} catch (UnsupportedEncodingException e) {
					}
				}

//				protected OutputBuf writeBytes(OutputBuf buf, byte[] src, int srcIndex, int length, HttpResponse response) {
//					if (!buf.writable()) {
//						response.addBody(buf.input());
//						buf.release();
//						buf = executor.allocBuffer();
//					}
//					while (length > 0) {
//						int canWriteCount = buf.writableBytes();
//						if (length > canWriteCount) {
//							buf.writeBytes(src, srcIndex, canWriteCount);
//							srcIndex += canWriteCount;
//							length -= canWriteCount;
//							response.addBody(buf.input());
//							buf.release();
//							buf = executor.allocBuffer();
//						} else {
//							buf.writeBytes(src, srcIndex, length);
//							break;
//						}
//					}
//					return buf;
//				}

				@Override
				public void error(HttpRequest request, int code) {
				}
			});
		}

	};

	static {
		ArrayList<Character> list = new ArrayList<Character>();
		for (int i = '0'; i <= '9'; ++i) {
			list.add((char) i);
		}
		for (int i = 'a'; i <= 'z'; ++i) {
			list.add((char) i);
		}
		for (int i = 'A'; i <= 'Z'; ++i) {
			list.add((char) i);
		}

		Character[] cs = list.toArray(new Character[list.size()]);
		IC = new char[cs.length];
		for (int i = 0; i < cs.length; ++i) {
			IC[i] = cs[i];
		}

	}

}
