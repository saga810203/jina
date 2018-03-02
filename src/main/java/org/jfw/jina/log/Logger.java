package org.jfw.jina.log;

import java.nio.ByteBuffer;

public interface Logger
{
    boolean enableTrace();
	boolean trace(String message);
	boolean trace(String message,Throwable t);
	boolean enableDebug();
	boolean debug(String message);
	boolean debug(String message,Throwable t);
	boolean enableInfo();
	boolean info(String message);
	boolean info(String message,Throwable t);
	boolean enableWarn();
	boolean warn(String message);
	boolean warn(String message,Throwable t);
	boolean error(String message);
	boolean error(String message,Throwable t);
	boolean fatal(String message);
	boolean fatal(String message,Throwable t);
	
	boolean debug(ByteBuffer buffer,int begin,int end);
	boolean debug(byte[] buffer,int begin,int end);
}
