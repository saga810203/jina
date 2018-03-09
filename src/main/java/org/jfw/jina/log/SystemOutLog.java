package org.jfw.jina.log;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class SystemOutLog implements Logger {

	public static final int ALL = 30;
	public static final int FATAL = 1;
	public static final int ERROR = 5;
	public static final int WARN = 10;
	public static final int INFO = 15;
	public static final int DEBUG = 20;
	public static final int TRACE = 25;
	public static final int DISABLE = 0;

	private static int LEVEL = INFO;

	private static final SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	public static final SystemOutLog INS = new SystemOutLog();

	private SystemOutLog() {
	}

	@Override
	public boolean enableTrace() {
		return LEVEL >= TRACE;
	}

	private static synchronized boolean out(String fn, String mn, int ln, String message) {
		System.out.println("===============================");
		System.out.print(sf.format(new Date()));
		System.out.print(":");
		System.out.print(fn);
		System.out.print(".");
		System.out.print(mn);
		System.out.print(" at line:");
		System.out.print(ln);
		System.out.println(":");
		System.out.println(message);
		System.out.println("===============================");
		return true;
	}

	private synchronized boolean out(String fn, String mn, int ln, String message, Throwable t) {
		System.out.println("===============================");
		System.out.print(sf.format(new Date()));
		System.out.print(":");
		System.out.print(fn);
		System.out.print(".");
		System.out.print(mn);
		System.out.print(" at line:");
		System.out.print(ln);
		System.out.println(":");
		System.out.println(message);
		t.printStackTrace(System.out);
		System.out.println("===============================");
		return true;
	}

	@Override
	public synchronized void trace(final String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		System.out.print(sf.format(new Date()));
		System.out.print(":");
		System.out.print(fn);
		System.out.print(".");
		System.out.print(mn);
		System.out.print(" at line:");
		System.out.print(ln);
		System.out.println(":");
		System.out.println(message);
	}

	@Override
	public synchronized boolean assertTrace(Object... message) {
		if (enableTrace()) {
			for (Object obj : message) {
				System.out.print(obj);
			}
			System.out.println("");
		}
		return true;
	}

	@Override
	public synchronized void trace(String message, Throwable t) {

		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		System.out.print(sf.format(new Date()));
		System.out.print(":");
		System.out.print(fn);
		System.out.print(".");
		System.out.print(mn);
		System.out.print(" at line:");
		System.out.print(ln);
		System.out.println(":");
		System.out.println(message);
		t.printStackTrace(System.out);
	}

	@Override
	public synchronized boolean assertTrace(Throwable t, Object... msg) {
		if (enableTrace()) {
			for (Object obj : msg) {
				System.out.print(obj);
			}
			System.out.println("");
			t.printStackTrace(System.out);
		}
		return true;
	}
	
	@Override
	public synchronized boolean assertDebug(Object... msg) {
		if(enableDebug()){
			StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
			String fn = ste.getFileName();
			String mn = ste.getMethodName();
			int ln = ste.getLineNumber();
			System.out.print(sf.format(new Date()));
			System.out.print(":");
			System.out.print(fn);
			System.out.print(".");
			System.out.print(mn);
			System.out.print(" at line:");
			System.out.print(ln);
			System.out.println(":");
			for (Object obj : msg) {
				System.out.print(obj);
			}
			System.out.println("");
		}
		return true;
	}

	@Override
	public synchronized boolean assertDebug(Throwable t, Object... msg) {
		if(enableDebug()){
			StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
			String fn = ste.getFileName();
			String mn = ste.getMethodName();
			int ln = ste.getLineNumber();
			System.out.print(sf.format(new Date()));
			System.out.print(":");
			System.out.print(fn);
			System.out.print(".");
			System.out.print(mn);
			System.out.print(" at line:");
			System.out.print(ln);
			System.out.println(":");
			for (Object obj : msg) {
				System.out.print(obj);
			}
			System.out.println("");
			t.printStackTrace(System.out);
		}
		return true;
	}

	@Override
	public boolean enableDebug() {
		return LEVEL >= DEBUG;
	}

	@Override
	public void debug(String message) {

		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message);
	}

	@Override
	public void debug(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message, t);
	}

	@Override
	public boolean enableInfo() {
		return LEVEL >= INFO;
	}

	@Override
	public void info(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message);
	}

	@Override
	public void info(String message, Throwable t) {

		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message, t);
	}

	@Override
	public boolean enableWarn() {
		return LEVEL >= WARN;
	}

	@Override
	public void warn(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message);
	}

	@Override
	public void warn(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message, t);
	}

	@Override
	public void error(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message);
	}

	@Override
	public void error(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message, t);

	}

	@Override
	public void fatal(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message);

	}

	@Override
	public void fatal(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		out(fn, mn, ln, message, t);

	}

	@Override
	public boolean assertTrace(ByteBuffer buffer, int begin, int end) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		if (buffer == null) {
			out(fn, mn, ln, "buffer is null");
			return true;
		}
		StringBuilder sb = new StringBuilder();
		if (end > begin && begin >= 0 && end <= buffer.capacity()) {
			int col = 0;
			for (int i = begin; i < end; ++i) {
				sb.append(Integer.toHexString(buffer.get(i) & 0xFF)).append(",");
				++col;
				if (col == 16) {
					col = 0;
					sb.append("\r\n");
				}
			}

		} else {
			sb.append("  buffer.capacity:").append(buffer.capacity()).append("  begin:").append(begin).append("  end:").append(end);
		}
		out(fn, mn, ln, sb.toString());
		return true;
	}

	@Override
	public boolean assertTrace(byte[] buffer, int begin, int end) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		if (buffer == null) {
			out(fn, mn, ln, "buffer is null");
			return true;
		}
		StringBuilder sb = new StringBuilder();
		if (end > begin && begin >= 0 && end <= buffer.length) {
			int col = 0;
			for (int i = begin; i < end; ++i) {
				sb.append("0x").append(Integer.toHexString(buffer[i] & 0xFF)).append(",");
				++col;
				if (col == 16) {
					col = 0;
					sb.append("\r\n");
				}
			}

		} else {
			sb.append("  buffer.length:").append(buffer.length).append("  begin:").append(begin).append("  end:").append(end);
		}
		out(fn, mn, ln, sb.toString());
		return true;
	}



	@Override
	public synchronized boolean assertInfo(Object... msg) {
		if(enableInfo()){
			StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
			String fn = ste.getFileName();
			String mn = ste.getMethodName();
			int ln = ste.getLineNumber();
			System.out.print(sf.format(new Date()));
			System.out.print(":");
			System.out.print(fn);
			System.out.print(".");
			System.out.print(mn);
			System.out.print(" at line:");
			System.out.print(ln);
			System.out.println(":");
			for (Object obj : msg) {
				System.out.print(obj);
			}
			System.out.println("");
		}
		return true;
	}

	@Override
	public synchronized boolean assertInfo(Throwable t, Object... msg) {
		if(enableInfo()){
			StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
			String fn = ste.getFileName();
			String mn = ste.getMethodName();
			int ln = ste.getLineNumber();
			System.out.print(sf.format(new Date()));
			System.out.print(":");
			System.out.print(fn);
			System.out.print(".");
			System.out.print(mn);
			System.out.print(" at line:");
			System.out.print(ln);
			System.out.println(":");
			for (Object obj : msg) {
				System.out.print(obj);
			}
			System.out.println("");
			t.printStackTrace(System.out);
		}
		return true;
	}

}
