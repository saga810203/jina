package org.jfw.jina.http2;

public final  class Http2ProtocolError {
	
	private Http2ProtocolError(){}
	
	public static final byte ERROR_MAX_FRAME_SIZE = -1;	
	public static final byte ERROR_FRAME_NOT_ASSOCIATED_STREAM = -2;
	public static final byte ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION = -3;
	public static final byte ERROR_FRAME_INVALID_FRAME_WITH_HEADER_CONTINUATION_NOT = -4;
	

	

	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PRIORITY = -5;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_RSTSTREAM = -6;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING = -7;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_SETTING_ACK = -8;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_PING = -9;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_GOAWAY = -10;
	public static final byte ERROR_FRAME_INVALID_PALYLOAD_LENGHT_WITH_WINDOWUPDATE = -11;	
	public static final byte ERROR_FRAME_INVALID_PAYLOAD_LENGTH = -12;  //WITH PADED;
	
	public static final byte ERROR_FRAME_INVALID_STREAM_ID_WITH_CONTINUATION = -13;

	
	public static final byte ERROR_FRAME_INVALID_WINDOW_UPDATE = -15;
	
	public static final byte ERROR_INVALID_SETTING_VALUE = -127;
	
	public static final byte ERROR_NOT_SUPPORTED = -128;
	
}
