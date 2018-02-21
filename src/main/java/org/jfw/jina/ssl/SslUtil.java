package org.jfw.jina.ssl;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLHandshakeException;


public final class SslUtil {

	// Protocols
	static final String PROTOCOL_SSL_V2_HELLO = "SSLv2Hello";
	static final String PROTOCOL_SSL_V2 = "SSLv2";
	static final String PROTOCOL_SSL_V3 = "SSLv3";
	static final String PROTOCOL_TLS_V1 = "TLSv1";
	static final String PROTOCOL_TLS_V1_1 = "TLSv1.1";
	static final String PROTOCOL_TLS_V1_2 = "TLSv1.2";

	/**
	 * change cipher spec
	 */
	static final int SSL_CONTENT_TYPE_CHANGE_CIPHER_SPEC = 20;

	/**
	 * alert
	 */
	public static final int SSL_CONTENT_TYPE_ALERT = 21;

	/**
	 * handshake
	 */
	public static final int SSL_CONTENT_TYPE_HANDSHAKE = 22;

	/**
	 * application data
	 */
	public static final int SSL_CONTENT_TYPE_APPLICATION_DATA = 23;

	/**
	 * HeartBeat Extension
	 */
	public static final int SSL_CONTENT_TYPE_EXTENSION_HEARTBEAT = 24;

	/**
	 * the length of the ssl record header (in bytes)
	 */
	public static final int SSL_RECORD_HEADER_LENGTH = 5;

	/**
	 * Not enough data in buffer to parse the record length
	 */
	public static final int NOT_ENOUGH_DATA = -1;

	/**
	 * data is not encrypted
	 */
	public static final int NOT_ENCRYPTED = -2;

	private SslUtil() {
	};

	public static SSLHandshakeException toSSLHandshakeException(Throwable e) {
		if (e instanceof SSLHandshakeException) {
			return (SSLHandshakeException) e;
		}

		return (SSLHandshakeException) new SSLHandshakeException(e.getMessage()).initCause(e);
	}

	public static int getEncryptedPacketLength(ByteBuffer buffer, int pos) {
		int packetLength = 0;
		// SSLv3 or TLS - Check ContentType
		boolean tls;
		switch (buffer.get(pos)) {
			case SSL_CONTENT_TYPE_CHANGE_CIPHER_SPEC:
			case SSL_CONTENT_TYPE_ALERT:
			case SSL_CONTENT_TYPE_HANDSHAKE:
			case SSL_CONTENT_TYPE_APPLICATION_DATA:
			case SSL_CONTENT_TYPE_EXTENSION_HEARTBEAT:
				tls = true;
				break;
			default:
				// SSLv2 or bad data
				tls = false;
		}

		if (tls) {
			// SSLv3 or TLS - Check ProtocolVersion
			int majorVersion = buffer.get(pos + 1);
			if (majorVersion == 3) {
				// SSLv3 or TLS
				packetLength = (buffer.getShort(pos + 3) & 0xFFFF) + SSL_RECORD_HEADER_LENGTH;
				if (packetLength <= SSL_RECORD_HEADER_LENGTH) {
					// Neither SSLv3 or TLSv1 (i.e. SSLv2 or bad data)
					tls = false;
				}
			} else {
				// Neither SSLv3 or TLSv1 (i.e. SSLv2 or bad data)
				tls = false;
			}
		}

		if (!tls) {
			// SSLv2 or bad data - Check the version
			int headerLength = ((buffer.get(pos) & 0xFF) & 0x80) != 0 ? 2 : 3;
			int majorVersion = buffer.get(pos + headerLength + 1) & 0xFF;
			if (majorVersion == 2 || majorVersion == 3) {
				// SSLv2
				short ts = buffer.getShort(pos);
				packetLength = headerLength == 2 ? (ts & 0x7FFF) + 2 : (ts & 0x3FFF) + 3;
				if (packetLength <= headerLength) {
					return NOT_ENOUGH_DATA;
				}
			} else {
				return NOT_ENCRYPTED;
			}
		}
		return packetLength;
	}

}
