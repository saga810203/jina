package org.jfw.jina.util;

import java.math.BigInteger;

public class Base64 {
	private static final int DEFAULT_BUFFER_RESIZE_FACTOR = 2;

	private static final int DEFAULT_BUFFER_SIZE = 8192;

	private static final byte[] STANDARD_ENCODE_TABLE = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
			'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
			'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/' };

	private static final byte[] URL_SAFE_ENCODE_TABLE = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
			'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
			'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_' };

	private static final byte PAD = '=';

	private static final byte[] DECODE_TABLE = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
			-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, 62, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1,
			0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63, -1, 26, 27, 28, 29, 30, 31, 32,
			33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51 };

	private static final int MASK_6BITS = 0x3f;

	private static final int MASK_8BITS = 0xff;

	private final byte[] encodeTable;

	private byte[] buffer;

	private int pos;

	private int readPos;

	private int modulus;

	private boolean eof;

	private int x;

	public Base64() {
		this(false);
	}

	public Base64(boolean urlSafe) {
		this.encodeTable = urlSafe ? URL_SAFE_ENCODE_TABLE : STANDARD_ENCODE_TABLE;
	}

	public boolean isUrlSafe() {
		return this.encodeTable == URL_SAFE_ENCODE_TABLE;
	}

	boolean hasData() {
		return this.buffer != null;
	}

	int avail() {
		return buffer != null ? pos - readPos : 0;
	}

	private void resizeBuffer() {
		if (buffer == null) {
			buffer = new byte[DEFAULT_BUFFER_SIZE];
			pos = 0;
			readPos = 0;
		} else {
			byte[] b = new byte[buffer.length * DEFAULT_BUFFER_RESIZE_FACTOR];
			System.arraycopy(buffer, 0, b, 0, buffer.length);
			buffer = b;
		}
	}

	int readResults(byte[] b, int bPos, int bAvail) {
		if (buffer != null) {
			int len = Math.min(avail(), bAvail);
			if (buffer != b) {
				System.arraycopy(buffer, readPos, b, bPos, len);
				readPos += len;
				if (readPos >= pos) {
					buffer = null;
				}
			} else {
				buffer = null;
			}
			return len;
		}
		return eof ? -1 : 0;
	}

	void setInitialBuffer(byte[] out, int outPos, int outAvail) {
		if (out != null && out.length == outAvail) {
			buffer = out;
			pos = outPos;
			readPos = outPos;
		}
	}

	void encode(byte[] in, int inPos, int inAvail) {
		if (eof) {
			return;
		}
		if (inAvail < 0) {
			eof = true;
			if (buffer == null || buffer.length - pos < 4) {
				resizeBuffer();
			}
			switch (modulus) {
				case 1:
					buffer[pos++] = encodeTable[(x >> 2) & MASK_6BITS];
					buffer[pos++] = encodeTable[(x << 4) & MASK_6BITS];
					if (encodeTable == STANDARD_ENCODE_TABLE) {
						buffer[pos++] = PAD;
						buffer[pos++] = PAD;
					}
					break;
				case 2:
					buffer[pos++] = encodeTable[(x >> 10) & MASK_6BITS];
					buffer[pos++] = encodeTable[(x >> 4) & MASK_6BITS];
					buffer[pos++] = encodeTable[(x << 2) & MASK_6BITS];
					if (encodeTable == STANDARD_ENCODE_TABLE) {
						buffer[pos++] = PAD;
					}
					break;
			}
		} else {
			for (int i = 0; i < inAvail; i++) {
				if (buffer == null || buffer.length - pos < 4) {
					resizeBuffer();
				}
				modulus = (++modulus) % 3;
				int b = in[inPos++];
				if (b < 0) {
					b += 256;
				}
				x = (x << 8) + b;
				if (0 == modulus) {
					buffer[pos++] = encodeTable[(x >> 18) & MASK_6BITS];
					buffer[pos++] = encodeTable[(x >> 12) & MASK_6BITS];
					buffer[pos++] = encodeTable[(x >> 6) & MASK_6BITS];
					buffer[pos++] = encodeTable[x & MASK_6BITS];
				}
			}
		}
	}

	void decode(byte[] in, int inPos, int inAvail) {
		if (eof) {
			return;
		}
		if (inAvail < 0) {
			eof = true;
		}
		for (int i = 0; i < inAvail; i++) {
			if (buffer == null || buffer.length - pos < 3) {
				resizeBuffer();
			}
			byte b = in[inPos++];
			if (b == PAD) {
				eof = true;
				break;
			} else {
				if (b >= 0 && b < DECODE_TABLE.length) {
					int result = DECODE_TABLE[b];
					if (result >= 0) {
						modulus = (++modulus) % 4;
						x = (x << 6) + result;
						if (modulus == 0) {
							buffer[pos++] = (byte) ((x >> 16) & MASK_8BITS);
							buffer[pos++] = (byte) ((x >> 8) & MASK_8BITS);
							buffer[pos++] = (byte) (x & MASK_8BITS);
						}
					}
				}
			}
		}

		if (eof && modulus != 0) {
			x = x << 6;
			switch (modulus) {
				case 2:
					x = x << 6;
					buffer[pos++] = (byte) ((x >> 16) & MASK_8BITS);
					break;
				case 3:
					buffer[pos++] = (byte) ((x >> 16) & MASK_8BITS);
					buffer[pos++] = (byte) ((x >> 8) & MASK_8BITS);
					break;
			}
		}
	}

	public static boolean isBase64(byte octet) {
		return octet == PAD || (octet >= 0 && octet < DECODE_TABLE.length && DECODE_TABLE[octet] != -1);
	}

	public static boolean isArrayByteBase64(byte[] arrayOctet) {
		for (int i = 0; i < arrayOctet.length; i++) {
			if (!isBase64(arrayOctet[i])) {
				return false;
			}
		}
		return true;
	}

	public static byte[] encodeBase64(byte[] binaryData) {
		return encodeBase64(binaryData, false);
	}



	public static byte[] encodeBase64URLSafe(byte[] binaryData) {
		return encodeBase64(binaryData, true);
	}




	public byte[] decode(byte[] pArray) {
		reset();
		if (pArray == null || pArray.length == 0) {
			return pArray;
		}
		long len = (pArray.length * 3) / 4;
		byte[] buf = new byte[(int) len];
		setInitialBuffer(buf, 0, buf.length);
		decode(pArray, 0, pArray.length);
		decode(pArray, 0, -1);
		byte[] result = new byte[pos];
		readResults(result, 0, result.length);
		return result;
	}

	public static byte[] encodeBase64(byte[] binaryData, boolean urlSafe) {
		return encodeBase64(binaryData, urlSafe, Integer.MAX_VALUE);
	}

	public static byte[] encodeBase64(byte[] binaryData, boolean urlSafe, int maxResultSize) {
		if (binaryData == null || binaryData.length == 0) {
			return binaryData;
		}

		long len = getEncodeLength(binaryData);
		if (len > maxResultSize) {
			throw new IllegalArgumentException(
					"Input array too big, the output array would be bigger (" + len + ") than the specified maxium size of " + maxResultSize);
		}

		Base64 b64 = new Base64(urlSafe);
		return b64.encode(binaryData);
	}



	/**
	 * Decodes Base64 data into octets
	 * 
	 * @param base64Data
	 *            Byte array containing Base64 data
	 * @return Array containing decoded data.
	 */
	public static byte[] decodeBase64(byte[] base64Data) {
		return new Base64().decode(base64Data);
	}



	public byte[] encode(byte[] pArray) {
		reset();
		if (pArray == null || pArray.length == 0) {
			return pArray;
		}
		long len = getEncodeLength(pArray);
		byte[] buf = new byte[(int) len];
		setInitialBuffer(buf, 0, buf.length);
		encode(pArray, 0, pArray.length);
		encode(pArray, 0, -1);
		if (buffer != buf) {
			readResults(buf, 0, buf.length);
		}
		if (isUrlSafe() && pos < buf.length) {
			byte[] smallerBuf = new byte[pos];
			System.arraycopy(buf, 0, smallerBuf, 0, pos);
			buf = smallerBuf;
		}
		return buf;
	}

	private static long getEncodeLength(byte[] pArray) {

		long len = (pArray.length * 4) / 3;
		long mod = len % 4;
		if (mod != 0) {
			len += 4 - mod;
		}
		return len;
	}

	public static BigInteger decodeInteger(byte[] pArray) {
		return new BigInteger(1, decodeBase64(pArray));
	}

	public static byte[] encodeInteger(BigInteger bigInt) {
		if (bigInt == null) {
			throw new NullPointerException("encodeInteger called with null parameter");
		}
		return encodeBase64(toIntegerBytes(bigInt), false);
	}

	static byte[] toIntegerBytes(BigInteger bigInt) {
		int bitlen = bigInt.bitLength();
		bitlen = ((bitlen + 7) >> 3) << 3;
		byte[] bigBytes = bigInt.toByteArray();

		if (((bigInt.bitLength() % 8) != 0) && (((bigInt.bitLength() / 8) + 1) == (bitlen / 8))) {
			return bigBytes;
		}
		int startSrc = 0;
		int len = bigBytes.length;
		if ((bigInt.bitLength() % 8) == 0) {
			startSrc = 1;
			len--;
		}
		int startDst = bitlen / 8 - len; // to pad w/ nulls as per spec
		byte[] resizedBytes = new byte[bitlen / 8];
		System.arraycopy(bigBytes, startSrc, resizedBytes, startDst, len);
		return resizedBytes;
	}

	private void reset() {
		buffer = null;
		pos = 0;
		readPos = 0;
		modulus = 0;
		eof = false;
	}
}
