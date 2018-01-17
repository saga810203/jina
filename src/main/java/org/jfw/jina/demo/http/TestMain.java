package org.jfw.jina.demo.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.jfw.jina.http.HttpConsts;

public class TestMain {
	public static void main(String[] args) throws IOException, InterruptedException{
	/*
		StringBuilder sb= new StringBuilder();
		sb.append( "POST /portal/ajax/leavemsg");
//		for(int i = 0 ;i < 2;++i ){
//			sb.append("/A");
//		}
		sb.append(" HTTP/1.1\\r\n");
		sb.append(HttpConsts.TRANSFER_ENCODING).append(":").append(HttpConsts.CHUNKED).append("\r\n\r\n");
		for(int i = 0 ;i < 8192;++i ){
		sb.append("AAAAA");
	}	
		testSocket(sb.toString());
		*/
		
		int min = 500;
		int max = 0;
		int cnt = min;
		int rmax = min;
		Map<Object,Integer> map =new HashMap<>();
		for(int i = 0 ; i <cnt; ++i){
		   int v = randomInt(rmax);
		   min = Integer.min(min,v);
		   max = Integer.max(max,v);
		   countTime(map,Integer.valueOf(v));
		}
		
		System.out.println("min:"+min);
		System.out.println("max:"+max);
		writeMap(map);
	}
	public static void countTime(Map<Object,Integer> map,Object key){
		Integer v = map.get(key);
		if(v==null){
			map.put(key, 1);
		}else{
			map.put(key,v+1);
		}
	}
	public static void writeMap(Map<Object,Integer> map){
		for(Map.Entry<Object,Integer> entry:map.entrySet()){
			System.out.println(entry.getKey()+":"+entry.getValue());
		}
	}

	public static void testSocket(String s) throws IOException, InterruptedException {
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

   
	
	private static final char[] IC;
	private static final Random random = new Random();
	
	
	private static int randomInt(int max){
		return random.nextInt(max);
	}
	
	private static String randomString(int max){
		int count = Integer.max(1, randomInt(max));
		StringBuilder sb= new StringBuilder(count);
		for(int i =0; i < count ; ++i){
			sb.append(rc());
		}
		return sb.toString();
	}
	
	
	private static char rc(){
		return IC[random.nextInt(IC.length)];
	}
	
	private static Map<String,String> randomHeader(int num){
		 Map<String,String> headers = new HashMap<String,String>();
			int count = Integer.max(1, randomInt(num));
		for(int i = 0 ; i < count;++i){
			String k ="JINA-"+randomString(30);
			String v = randomString(500);
			headers.put(k, v);
		}
    return headers;
		 
		
	}
	
	 static class TestHttpRequst{
		 String method;
		 String path;
		 String queryString;
		 String hash;
		 Map<String,String> headers;
		 String body;
		 boolean close;
		
	}
	
	
	
	
	static{
		ArrayList<Character> list = new ArrayList<Character>();
		for(int i ='0';i <='9';++i){
			list.add((char)i);
		}
		for(int i ='a';i <='z';++i){
			list.add((char)i);
		}
		for(int i ='A';i <='Z';++i){
			list.add((char)i);
		}
		
		Character[] cs = list.toArray(new Character[list.size()]);
		IC = new char[cs.length];
		for(int i =0; i < cs.length;++i){
			IC[i]= cs[i];
		}
		
		
	}

}
