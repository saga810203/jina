package org.jfw.jina.ssl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jfw.jina.util.Base64;
import org.jfw.jina.util.StringUtil;

public class SelfSignedCertificate {
	/**
	 * Current time minus 1 year, just in case software clock goes back due to
	 * time synchronization
	 */
	private static final Date DEFAULT_NOT_BEFORE = new Date(System.currentTimeMillis() - 86400000L * 365);
	/**
	 * The maximum possible value in X.509 specification: 9999-12-31 23:59:59
	 */
	private static final Date DEFAULT_NOT_AFTER = new Date(253402300799000L);

	private final File certificate;
	private final File privateKey;
	private final X509Certificate cert;
	private final PrivateKey key;
	private byte[] privateKeyContent;
	private byte[] certificateContent;

	public SelfSignedCertificate() throws CertificateException {
		this(DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
	}

	public SelfSignedCertificate(Date notBefore, Date notAfter) throws CertificateException {
		this("jina.org", notBefore, notAfter);
	}

	public SelfSignedCertificate(String fqdn) throws CertificateException {
		this(fqdn, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
	}

	/**
	 * Creates a new instance.
	 *
	 * @param fqdn
	 *            a fully qualified domain name
	 * @param notBefore
	 *            Certificate is not valid before this time
	 * @param notAfter
	 *            Certificate is not valid after this time
	 */
	public SelfSignedCertificate(String fqdn, Date notBefore, Date notAfter) throws CertificateException {
		this(fqdn, new SecureRandom(), 1024, notBefore, notAfter);
	}

	/**
	 * Creates a new instance.
	 *
	 * @param fqdn
	 *            a fully qualified domain name
	 * @param random
	 *            the {@link java.security.SecureRandom} to use
	 * @param bits
	 *            the number of bits of the generated private key
	 */
	public SelfSignedCertificate(String fqdn, SecureRandom random, int bits) throws CertificateException {
		this(fqdn, random, bits, DEFAULT_NOT_BEFORE, DEFAULT_NOT_AFTER);
	}

	/**
	 * Creates a new instance.
	 *
	 * @param fqdn
	 *            a fully qualified domain name
	 * @param random
	 *            the {@link java.security.SecureRandom} to use
	 * @param bits
	 *            the number of bits of the generated private key
	 * @param notBefore
	 *            Certificate is not valid before this time
	 * @param notAfter
	 *            Certificate is not valid after this time
	 */
	public SelfSignedCertificate(String fqdn, SecureRandom random, int bits, Date notBefore, Date notAfter) throws CertificateException {
		// Generate an RSA key pair.
		final KeyPair keypair;
		try {
			KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
			keyGen.initialize(bits, random);
			keypair = keyGen.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			// Should not reach here because every Java implementation must have
			// RSA key pair generator.
			throw new Error(e);
		}

		String[] paths;

		try {

			paths = generate(fqdn, keypair, random, notBefore, notAfter);
		} catch (Throwable t2) {
			throw new CertificateException("No provider succeeded to generate a self-signed certificate. " + "See debug log for the root cause.", t2);

		}

		certificate = new File(paths[0]);
		privateKey = new File(paths[1]);
		key = keypair.getPrivate();
		FileInputStream certificateInput = null;
		try {
			certificateInput = new FileInputStream(certificate);
			cert = (X509Certificate) CertificateFactory.getInstance("X509").generateCertificate(certificateInput);
		} catch (Exception e) {
			throw new CertificateEncodingException(e);
		} finally {
			if (certificateInput != null) {
				try {
					certificateInput.close();
				} catch (IOException e) {
					// TODO: LOG
				}
			}
		}
	}

	public File certificate() {
		return certificate;
	}

	public File privateKey() {
		return privateKey;
	}

	/**
	 * Returns the generated X.509 certificate.
	 */
	public X509Certificate cert() {
		return cert;
	}

	/**
	 * Returns the generated RSA private key.
	 */
	public PrivateKey key() {
		return key;
	}
	
	

	public byte[] getPrivateKeyContent() {
		return privateKeyContent;
	}

	public byte[] getCertificateContent() {
		return certificateContent;
	}

	/**
	 * Deletes the generated X.509 certificate file and RSA private key file.
	 */
	public void delete() {
		safeDelete(certificate);
		safeDelete(privateKey);
	}

	public static final byte[] PRIVATE_KEY_PRXFIX = "-----BEGIN PRIVATE KEY-----\n".getBytes(StringUtil.US_ASCII);
	public static final byte[] PRIVATE_KEY_SUFFIX = "\n-----END PRIVATE KEY-----\n".getBytes(StringUtil.US_ASCII);
	public static final byte[] CERTIFICATE_PRXFIX = "-----BEGIN CERTIFICATE-----\n".getBytes(StringUtil.US_ASCII);
	public static final byte[] CERTIFICATE_SUFFIX = "\n-----END CERTIFICATE-----\n".getBytes(StringUtil.US_ASCII);

	public String[] newSelfSignedCertificate(String fqdn, PrivateKey key, X509Certificate cert) throws IOException, CertificateEncodingException {
		byte[] buffer = Base64.encodeBase64(key.getEncoded(), false);
		int len = buffer.length - 1;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(PRIVATE_KEY_PRXFIX);
		int lineNum = 0;
		for (int i = 0; i < len; ++i) {
			++lineNum;
			out.write(buffer[i]);
			if (lineNum == 76) {
				lineNum = 0;
				out.write(10);
			}
		}
		out.write(buffer[len]);
		out.write(PRIVATE_KEY_SUFFIX);

		this.privateKeyContent = out.toByteArray();
		out.reset();

		File keyFile = File.createTempFile("keyutil_" + fqdn + '_', ".key");
		keyFile.deleteOnExit();

		OutputStream keyOut = new FileOutputStream(keyFile);
		try {
			keyOut.write(this.privateKeyContent);
			keyOut.flush();
			keyOut.close();
			keyOut = null;
		} finally {
			if (keyOut != null) {
				safeClose(keyFile, keyOut);
				safeDelete(keyFile);
			}
		}

		buffer = Base64.encodeBase64(cert.getEncoded(), false);
		len = buffer.length - 1;
		out.reset();

		out.write(CERTIFICATE_PRXFIX);
		lineNum = 0;
		for (int i = 0; i < len; ++i) {
			++lineNum;
			out.write(buffer[i]);
			if (lineNum == 76) {
				lineNum = 0;
				out.write(10);
			}
		}
		out.write(buffer[len]);
		out.write(CERTIFICATE_SUFFIX);

		this.certificateContent = out.toByteArray();

		File certFile = File.createTempFile("keyutil_" + fqdn + '_', ".crt");
		certFile.deleteOnExit();

		OutputStream certOut = new FileOutputStream(certFile);
		try {
			certOut.write(certificateContent);
			certOut.flush();
			certOut.close();
			certOut = null;
		} finally {
			if (certOut != null) {
				safeClose(certFile, certOut);
				safeDelete(certFile);
				safeDelete(keyFile);
			}
		}

		return new String[] { certFile.getPath(), keyFile.getPath() };
	}

	private static void safeDelete(File certFile) {
		if (!certFile.delete()) {
			// TODO:LOG
		}
	}

	private static void safeClose(File keyFile, OutputStream keyOut) {
		try {
			keyOut.close();
		} catch (IOException e) {
			// TODO:LOG
			// logger.warn("Failed to close a file: " + keyFile, e);
		}
	}

	private String[] generate(String fqdn, KeyPair keypair, SecureRandom random, Date notBefore, Date notAfter) throws Exception {
		PrivateKey key = keypair.getPrivate();
		// Prepare the information required for generating an X.509 certificate.
		X500Name owner = new X500Name("CN=" + fqdn);
		X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(owner, new BigInteger(64, random), notBefore, notAfter, owner, keypair.getPublic());

		ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(key);
		X509CertificateHolder certHolder = builder.build(signer);
		X509Certificate cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(certHolder);
		cert.verify(keypair.getPublic());
		return newSelfSignedCertificate(fqdn, key, cert);
	}

	private static final Provider PROVIDER = new BouncyCastleProvider();
}
