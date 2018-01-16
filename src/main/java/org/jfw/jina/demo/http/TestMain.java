package org.jfw.jina.demo.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

public class TestMain {
	public static void main(String[] args) throws IOException, InterruptedException{
	
		StringBuilder sb= new StringBuilder();
		sb.append( "GET ");
		for(int i = 0 ;i < 8192;++i ){
			sb.append('A');
		}
		sb.append(" HTTP/1.1\r\n\r\n");
		testSocket(sb.toString());
		
	}

	public static void testSocket(String s) throws IOException, InterruptedException {
		byte[] buf = s.getBytes("UTF-8");
		Socket socket = new Socket("www.sina.com", 80);
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
			socket.shutdownOutput();
			lock.await();
		} finally {
			socket.close();
		}
	}
}
