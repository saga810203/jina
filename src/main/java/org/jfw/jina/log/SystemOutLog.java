package org.jfw.jina.log;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class SystemOutLog implements Logger {

	private static final SimpleDateFormat sf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	public static final SystemOutLog INS = new SystemOutLog();

	private SystemOutLog() {
	}

	@Override
	public boolean enableTrace() {
		return true;
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
	public boolean trace(final String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);
	}

	@Override
	public boolean trace(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean enableDebug() {
		return true;
	}

	@Override
	public boolean debug(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);

	}

	@Override
	public boolean debug(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean enableInfo() {
		return true;
	}

	@Override
	public boolean info(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);
	}

	@Override
	public boolean info(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean enableWarn() {
		return true;
	}

	@Override
	public boolean warn(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);
	}

	@Override
	public boolean warn(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean error(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);
	}

	@Override
	public boolean error(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean fatal(String message) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message);

	}

	@Override
	public boolean fatal(String message, Throwable t) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		return out(fn, mn, ln, message, t);

	}

	@Override
	public boolean debug(ByteBuffer buffer, int begin, int end) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		if(buffer==null){
			return out(fn,mn,ln,"buffer is null");
		}
		StringBuilder sb = new StringBuilder();
		if (end > begin && begin >=0 && end <= buffer.capacity()) {
			int col = 0;
			for (int i = begin; i < end; ++i) {
				sb.append(Integer.toHexString(buffer.get(i) & 0xFF)).append(",");
				++col;
				if (col == 16) {
					col = 0;
					sb.append("\r\n");
				}
			}

		}else{
			sb.append("  buffer.capacity:").append(buffer.capacity()).append("  begin:").append(begin).append("  end:").append(end);
		}
		return out(fn,mn,ln,sb.toString());
	}

	@Override
	public boolean debug(byte[] buffer, int begin, int end) {
		StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
		String fn = ste.getFileName();
		String mn = ste.getMethodName();
		int ln = ste.getLineNumber();
		if(buffer==null){
			return out(fn,mn,ln,"buffer is null");
		}
		StringBuilder sb = new StringBuilder();
		if (end > begin && begin >=0 && end <= buffer.length) {
			int col = 0;
			for (int i = begin; i < end; ++i) {
				sb.append("0x").append(Integer.toHexString(buffer[i] & 0xFF)).append(",");
				++col;
				if (col == 16) {
					col = 0;
					sb.append("\r\n");
				}
			}

		}else{
			sb.append("  buffer.length:").append(buffer.length).append("  begin:").append(begin).append("  end:").append(end);
		}
		return out(fn,mn,ln,sb.toString());
	}

}
