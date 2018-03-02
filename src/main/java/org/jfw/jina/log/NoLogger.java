package org.jfw.jina.log;

import java.nio.ByteBuffer;

public final class NoLogger implements Logger
{
	public static final NoLogger INS = new NoLogger();
	
	private NoLogger() {
	}
	@Override
    public boolean trace(String message)
    {
	 return true;  	    
    }

	@Override
    public boolean trace(String message, Throwable t)
    {
		return true;
    }

	@Override
    public boolean debug(String message)
    {
		return true;
    }

	@Override
    public boolean debug(String message, Throwable t)
    {
		return true;
    }

	@Override
    public boolean info(String message)
    {
		return true;
    }

	@Override
    public boolean info(String message, Throwable t)
    {
		return true;
    }

	@Override
    public boolean warn(String message)
    {
		return true;
    }

	@Override
    public boolean warn(String message, Throwable t)
    {
		return true;
    }

	@Override
    public boolean error(String message)
    {
		return true;
    }

	@Override
    public boolean error(String message, Throwable t)
    {
		return true;
    }

	@Override
    public boolean fatal(String message)
    {
		return true; 
    }

	@Override
    public boolean fatal(String message, Throwable t)
    {
	    return true;
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
	public boolean debug(ByteBuffer buffer, int begin, int end) {
		return true;
	}
	@Override
	public boolean debug(byte[] buffer, int begin, int end) {
		return true;
	}

}
