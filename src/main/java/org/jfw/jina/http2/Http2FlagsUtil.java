package org.jfw.jina.http2;

public final class Http2FlagsUtil {
	
	
	
	
	public static final byte END_STREAM = 0x1;
	public static final byte END_HEADERS = 0x4;
	public static final byte ACK = 0x1;
	public static final byte PADDED = 0x8;
	public static final byte PRIORITY = 0x20;
	
	
	public static final byte END_STREAM_END_HEADERS = END_STREAM | END_HEADERS;

	private Http2FlagsUtil() {
	}

	/**
	 * Determines whether the {@link #END_STREAM} flag is set. Only applies to
	 * DATA and HEADERS frames.
	 */
	public static boolean endOfStream(byte value) {
		return (value & END_STREAM) != 0;
	}

	/**
	 * Determines whether the {@link #END_HEADERS} flag is set. Only applies for
	 * HEADERS, PUSH_PROMISE, and CONTINUATION frames.
	 */
	public static boolean endOfHeaders(byte value) {
		return (value & END_HEADERS) != 0;
	}

	/**
	 * Determines whether the flag is set indicating the presence of the
	 * exclusive, stream dependency, and weight fields in a HEADERS frame.
	 */
	public static boolean priorityPresent(byte value) {
		return (value & PRIORITY) != 0;
	}

	/**
	 * Determines whether the flag is set indicating that this frame is an ACK.
	 * Only applies for SETTINGS and PING frames.
	 */
	public static boolean ack(byte value) {
		return (value & ACK) != 0;
	}

	/**
	 * For frames that include padding, indicates if the {@link #PADDED} field
	 * is present. Only applies to DATA, HEADERS, PUSH_PROMISE and CONTINUATION
	 * frames.
	 */
	public static boolean paddingPresent(byte value) {
		return (value & PADDED) != 0;
	}

	/**
	 * Gets the number of bytes expected for the priority fields of the payload.
	 * This is determined by the {@link #priorityPresent()} flag.
	 */
	public static int getNumPriorityBytes(byte value) {
		return (value & PRIORITY) != 0 ? 5 : 0;
	}

	/**
	 * Gets the length in bytes of the padding presence field expected in the
	 * payload. This is determined by the {@link #paddingPresent()} flag.
	 */
	public static int getPaddingPresenceFieldLength(byte value) {
		return (value & PADDED) != 0 ? 1 : 0;
	}

	/**
	 * Sets the {@link #END_STREAM} flag.
	 */
	public static byte endOfStream(byte value, boolean on) {
		if (on) {
			return (byte) (value | END_STREAM);
		} else {
			return (byte) (value & ~END_STREAM);
		}

	}

	/**
	 * Sets the {@link #END_HEADERS} flag.
	 */
	public static byte endOfHeaders(byte value, boolean on) {
		if (on) {
			return (byte) (value | END_HEADERS);
		} else {
			return (byte) (value & ~END_HEADERS);
		}

	}

	/**
	 * Sets the {@link #PRIORITY} flag.
	 */
	public static byte priorityPresent(byte value, boolean on) {
		if (on) {
			return (byte) (value | PRIORITY);
		} else {
			return (byte) (value & ~PRIORITY);
		}
	}

	/**
	 * Sets the {@link #PADDED} flag.
	 */
	public static byte paddingPresent(byte value, boolean on) {
		if (on) {
			return (byte) (value | PADDED);
		} else {
			return (byte) (value & ~PADDED);
		}
	}

	/**
	 * Sets the {@link #ACK} flag.
	 */
	public static byte ack(byte value, boolean on) {
		if (on) {
			return (byte) (value | ACK);
		} else {
			return (byte) (value & ~ACK);
		}
	}

}
