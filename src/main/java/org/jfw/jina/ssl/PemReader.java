package org.jfw.jina.ssl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jfw.jina.util.Base64;
import org.jfw.jina.util.StringUtil;

public final class PemReader {

	private static final Pattern CERT_PATTERN = Pattern.compile(
			"-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
					"([a-z0-9+/=\\r\\n]+)" + // Base64 text
					"-+END\\s+.*CERTIFICATE[^-]*-+", // Footer
			Pattern.CASE_INSENSITIVE);
	private static final Pattern KEY_PATTERN = Pattern.compile(
			"-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
					"([a-z0-9+/=\\r\\n]+)" + // Base64 text
					"-+END\\s+.*PRIVATE\\s+KEY[^-]*-+", // Footer
			Pattern.CASE_INSENSITIVE);

	public static List<byte[]> readCertificates(File file) throws CertificateException {
		InputStream in = null;
		try {
			in = new FileInputStream(file);
			return readCertificates(in);
		} catch (Exception e) {
			throw new CertificateException("failed to read certificate file", e);
		} finally {
			if (in != null)
				safeClose(in);
		}
	}

	public static List<byte[]> readCertificates(InputStream in) throws CertificateException {
		String content;
		try {
			content = readContent(in);
		} catch (IOException e) {
			throw new CertificateException("failed to read certificate input stream", e);
		}

		List<byte[]> certs = new ArrayList<byte[]>();
		Matcher m = CERT_PATTERN.matcher(content);
		int start = 0;
		for (;;) {
			if (!m.find(start)) {
				break;
			}

			byte[] base64 = m.group(1).getBytes(StringUtil.US_ASCII);
			byte[] der = Base64.decodeBase64(base64);

			certs.add(der);

			start = m.end();
		}

		if (certs.isEmpty()) {
			throw new CertificateException("found no certificates in input stream");
		}
		return certs;
	}

	public static List<byte[]> readCertificates(byte[] in) throws CertificateException {
		String content = new String(in, StringUtil.US_ASCII);
		List<byte[]> certs = new ArrayList<byte[]>();
		Matcher m = CERT_PATTERN.matcher(content);
		int start = 0;
		for (;;) {
			if (!m.find(start)) {
				break;
			}

			byte[] base64 = m.group(1).getBytes(StringUtil.US_ASCII);
			byte[] der = Base64.decodeBase64(base64);

			certs.add(der);

			start = m.end();
		}

		if (certs.isEmpty()) {
			throw new CertificateException("found no certificates in input stream");
		}
		return certs;
	}

	public static byte[] readPrivateKey(File file) throws KeyException {
		try {
			InputStream in = new FileInputStream(file);

			try {
				return readPrivateKey(in);
			} finally {
				safeClose(in);
			}
		} catch (FileNotFoundException e) {
			throw new KeyException("could not find key file: " + file);
		}
	}

	public static byte[] readPrivateKey(InputStream in) throws KeyException {
		String content;
		try {
			content = readContent(in);
		} catch (IOException e) {
			throw new KeyException("failed to read key input stream", e);
		}

		Matcher m = KEY_PATTERN.matcher(content);
		if (!m.find()) {
			throw new KeyException("could not find a PKCS #8 private key in input stream"
					+ " (see http://netty.io/wiki/sslcontextbuilder-and-private-key.html for more information)");
		}

		byte[] base64 = m.group(1).getBytes(StringUtil.US_ASCII);
		byte[] der = Base64.decodeBase64(base64);
		return der;
	}

	public static byte[] readPrivateKey(byte[] in) throws KeyException {
		String content = new String(in, StringUtil.US_ASCII);
		Matcher m = KEY_PATTERN.matcher(content);
		if (!m.find()) {
			throw new KeyException("could not find a PKCS #8 private key in input stream"
					+ " (see http://netty.io/wiki/sslcontextbuilder-and-private-key.html for more information)");
		}

		byte[] base64 = m.group(1).getBytes(StringUtil.US_ASCII);
		byte[] der = Base64.decodeBase64(base64);
		return der;
	}

	private static String readContent(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			byte[] buf = new byte[8192];
			for (;;) {
				int ret = in.read(buf);
				if (ret < 0) {
					break;
				}
				out.write(buf, 0, ret);
			}
			return out.toString(StringUtil.US_ASCII.name());
		} finally {
			safeClose(out);
		}
	}

	private static void safeClose(InputStream in) {
		try {
			in.close();
		} catch (IOException e) {
		}
	}

	private static void safeClose(OutputStream out) {
		try {
			out.close();
		} catch (IOException e) {
		}
	}

	private PemReader() {
	}
}
