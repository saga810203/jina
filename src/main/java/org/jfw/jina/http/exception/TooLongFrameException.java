package org.jfw.jina.http.exception;

public class TooLongFrameException extends Exception{
	private static final long serialVersionUID = -6644033494854804400L;

	public TooLongFrameException(String message, Throwable cause) {
		super(message, cause);
	}

	public TooLongFrameException(String message) {
		super(message);
	}
	public TooLongFrameException() {
		super();
	}
}
