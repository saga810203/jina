package org.jfw.jina.log;

import java.nio.ByteBuffer;

public final class NoLogger implements Logger
{
	public static final NoLogger INS = new NoLogger();
	
	private NoLogger() {
	}
	@Override
    public void trace(String message)
    {
    }

	@Override
    public void trace(String message, Throwable t)
    {
    }

	@Override
    public void debug(String message)
    {
		
    }

	@Override
    public void debug(String message, Throwable t)
    {
		
    }

	@Override
    public void info(String message)
    {
		
    }

	@Override
    public void info(String message, Throwable t)
    {
		
    }

	@Override
    public void warn(String message)
    {
		
    }

	@Override
    public void warn(String message, Throwable t)
    {
		
    }

	@Override
    public void error(String message)
    {
		
    }

	@Override
    public void error(String message, Throwable t)
    {
		
    }

	@Override
    public void fatal(String message)
    {
		 
    }

	@Override
    public void fatal(String message, Throwable t)
    {
	    
    }

    public boolean enableTrace() {
        return false;
    }

    public boolean enableDebug() {
        return false;
    }

    public boolean enableInfo() {
        return false;
    }

    public boolean enableWarn() {
        return false;
    }
	@Override
	public boolean assertTrace(Object... msg) {
		return true;
	}
	@Override
	public boolean assertTrace(Throwable t, Object... msg) {
		return true;
	}
	@Override
	public boolean assertDebug(Object... msg) {
		return true;
	}
	@Override
	public boolean assertDebug(Throwable t, Object... msg) {
		return true;
	}
	@Override
	public boolean assertInfo(Object... msg) {
		return true;
	}
	@Override
	public boolean assertInfo(Throwable t, Object... msg) {
		return true;
	}
	@Override
	public boolean assertTrace(ByteBuffer buffer, int begin, int end) {
		return true;
	}
	@Override
	public boolean assertTrace(byte[] buffer, int begin, int end) {
		return true;
	}

}
