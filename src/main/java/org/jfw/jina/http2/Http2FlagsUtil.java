package org.jfw.jina.http2;

public final class Http2FlagsUtil {
	public static final short END_STREAM = 0x1;
	public static final short END_HEADERS = 0x4;
	public static final short ACK = 0x1;
	public static final short PADDED = 0x8;
	public static final short PRIORITY = 0x20;

	private Http2FlagsUtil() {
	}

	/**
	 * Determines whether the {@link #END_STREAM} flag is set. Only applies to
	 * DATA and HEADERS frames.
	 */
	public boolean endOfStream(short value) {
		return (value & END_STREAM) != 0;
	}

	/**
	 * Determines whether the {@link #END_HEADERS} flag is set. Only applies for
	 * HEADERS, PUSH_PROMISE, and CONTINUATION frames.
	 */
	public boolean endOfHeaders(short value) {
		return (value & END_HEADERS) != 0;
	}

	/**
	 * Determines whether the flag is set indicating the presence of the
	 * exclusive, stream dependency, and weight fields in a HEADERS frame.
	 */
	public boolean priorityPresent(short value) {
		return (value & PRIORITY) != 0;
	}

	/**
	 * Determines whether the flag is set indicating that this frame is an ACK.
	 * Only applies for SETTINGS and PING frames.
	 */
	public boolean ack(short value) {
		return (value & ACK) != 0;
	}

	/**
	 * For frames that include padding, indicates if the {@link #PADDED} field
	 * is present. Only applies to DATA, HEADERS, PUSH_PROMISE and CONTINUATION
	 * frames.
	 */
	public boolean paddingPresent(short value) {
		return (value & PADDED) != 0;
	}

	/**
	 * Gets the number of bytes expected for the priority fields of the payload.
	 * This is determined by the {@link #priorityPresent()} flag.
	 */
	public int getNumPriorityBytes(short value) {
		return (value & PRIORITY) != 0 ? 5 : 0;
	}

	/**
	 * Gets the length in bytes of the padding presence field expected in the
	 * payload. This is determined by the {@link #paddingPresent()} flag.
	 */
	public int getPaddingPresenceFieldLength(short value) {
		return (value & PADDED) != 0 ? 1 : 0;
	}

	/**
	 * Sets the {@link #END_STREAM} flag.
	 */
	public short endOfStream(short value, boolean on) {
		if (on) {
			return (short) (value | END_STREAM);
		} else {
			return (short) (value & ~END_STREAM);
		}

	}

	/**
	 * Sets the {@link #END_HEADERS} flag.
	 */
	public short endOfHeaders(short value, boolean on) {
		if (on) {
			return (short) (value | END_HEADERS);
		} else {
			return (short) (value & ~END_HEADERS);
		}

	}

	/**
	 * Sets the {@link #PRIORITY} flag.
	 */
	public short priorityPresent(short value, boolean on) {
		if (on) {
			return (short) (value | PRIORITY);
		} else {
			return (short) (value & ~PRIORITY);
		}
	}

	/**
	 * Sets the {@link #PADDED} flag.
	 */
	public short paddingPresent(short value, boolean on) {
		if (on) {
			return (short) (value | PADDED);
		} else {
			return (short) (value & ~PADDED);
		}
	}

	/**
	 * Sets the {@link #ACK} flag.
	 */
	public short ack(short value, boolean on) {
		if (on) {
			return (short) (value | ACK);
		} else {
			return (short) (value & ~ACK);
		}
	}

}
