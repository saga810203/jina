package org.jfw.jina.http2;

public final class Http2ProtocolError {

	private Http2ProtocolError() {
	}

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
	public static final byte ERROR_FRAME_INVALID_PAYLOAD_LENGTH = -12; // WITH
																		// PADED;

	public static final byte ERROR_FRAME_INVALID_STREAM_ID_WITH_CONTINUATION = -13;

	public static final byte ERROR_FRAME_INVALID_WINDOW_UPDATE = -15;
	public static final byte ERROR_INVALID_STREAM_ID = -20;

	public static final byte ERROR_INVALID_CONTENT_IN_HEADER_FRAME = -30;

	public static final byte ERROR_INVALID_SETTING_VALUE = -127;

	public static final byte ERROR_NOT_SUPPORTED = -128;

	public static final byte NO_ERROR = 0x0;
	public static final byte PROTOCOL_ERROR = 0x1;
	public static final byte INTERNAL_ERROR = 0x2;
	public static final byte FLOW_CONTROL_ERROR = 0x3;
	public static final byte SETTINGS_TIMEOUT = 0x4;
	public static final byte STREAM_CLOSED = 0x5;
	public static final byte FRAME_SIZE_ERROR = 0x6;
	public static final byte REFUSED_STREAM = 0x7;
	public static final byte CANCEL = 0x8;
	public static final byte COMPRESSION_ERROR = 0x9;
	public static final byte CONNECT_ERROR = 0xA;
	public static final byte ENHANCE_YOUR_CALM = 0xB;
	public static final byte INADEQUATE_SECURITY = 0xC;
	public static final byte HTTP_1_1_REQUIRED = 0xD;

}
