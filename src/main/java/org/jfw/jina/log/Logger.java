package org.jfw.jina.log;

import java.nio.ByteBuffer;
/**
 * 
 * @author Saga
 *  trace<<debug<<info<<warn<<error<<fatal
 */
public interface Logger
{
    boolean enableTrace();
	void trace(String message);
	boolean assertTrace(Object... msg);
	void trace(String message,Throwable t);
	boolean assertTrace(Throwable t,Object... msg);
	boolean enableDebug();
	void debug(String message);
	boolean assertDebug(Object... msg);
	void debug(String message,Throwable t);
	boolean assertDebug(Throwable t,Object... msg);
	boolean enableInfo();
	void info(String message);
	boolean assertInfo(Object... msg);
	void info(String message,Throwable t);
	boolean assertInfo(Throwable t,Object... msg);
	boolean enableWarn();
	void warn(String message);
	void warn(String message,Throwable t);
	void error(String message);
	void error(String message,Throwable t);
	void fatal(String message);
	void fatal(String message,Throwable t);	
	boolean assertTrace(ByteBuffer buffer,int begin,int end);
	boolean assertTrace(byte[] buffer,int begin,int end);
}
