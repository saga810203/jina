package org.jfw.jina.ssl;

import static java.util.Arrays.asList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.jfw.jina.buffer.BufAllocator;
import org.jfw.jina.ssl.ApplicationProtocolConfig.Protocol;
import org.jfw.jina.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import org.jfw.jina.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import org.jfw.jina.util.ArrayUtil;

public abstract class SslContext {
	static final CertificateFactory X509_CERT_FACTORY;
	public static final List<String> CIPHERS;
	private static final List<String> CIPHERS_JAVA_MOZILLA_MODERN_SECURITY = Collections.unmodifiableList(Arrays.asList(
			/* openssl = ECDHE-ECDSA-AES256-GCM-SHA384 */
			"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
			/* openssl = ECDHE-RSA-AES256-GCM-SHA384 */
			"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
			/* openssl = ECDHE-ECDSA-CHACHA20-POLY1305 */
			"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
			/* openssl = ECDHE-RSA-CHACHA20-POLY1305 */
			"TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
			/* openssl = ECDHE-ECDSA-AES128-GCM-SHA256 */
			"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
			/* REQUIRED BY HTTP/2 SPEC */
			/* openssl = ECDHE-RSA-AES128-GCM-SHA256 */
			"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
	/* REQUIRED BY HTTP/2 SPEC */
	));
	  static final String[] DEFAULT_CIPHER_SUITES = {
		        // GCM (Galois/Counter Mode) requires JDK 8.
		        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
		        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
		        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
		        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
		        // AES256 requires JCE unlimited strength jurisdiction policy files.
		        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
		        // GCM (Galois/Counter Mode) requires JDK 8.
		        "TLS_RSA_WITH_AES_128_GCM_SHA256",
		        "TLS_RSA_WITH_AES_128_CBC_SHA",
		        // AES256 requires JCE unlimited strength jurisdiction policy files.
		        "TLS_RSA_WITH_AES_256_CBC_SHA"
		    };

	static {
		CIPHERS = Collections.unmodifiableList(new ArrayList<String>(CIPHERS_JAVA_MOZILLA_MODERN_SECURITY));

		try {
			X509_CERT_FACTORY = CertificateFactory.getInstance("X.509");
		} catch (CertificateException e) {
			throw new IllegalStateException("unable to instance X.509 CertificateFactory", e);
		}
	}

	private final boolean startTls;

	

	/**
	 * Creates a new instance (startTls set to {@code false}).
	 */
	protected SslContext() {
		this(false);
	}

	/**
	 * Creates a new instance.
	 */
	protected SslContext(boolean startTls) {
		this.startTls = startTls;
	}

	/**
	 * Returns {@code true} if and only if this context is for server-side.
	 */
	public final boolean isServer() {
		return !isClient();
	}

	/**
	 * Returns the {@code true} if and only if this context is for client-side.
	 */
	public abstract boolean isClient();

	/**
	 * Returns the list of enabled cipher suites, in the order of preference.
	 */
	public abstract List<String> cipherSuites();

	/**
	 * Returns the size of the cache used for storing SSL session objects.
	 */
	public abstract long sessionCacheSize();

	/**
	 * Returns the timeout for the cached SSL session objects, in seconds.
	 */
	public abstract long sessionTimeout();


	/**
	 * Creates a new {@link SSLEngine}.
	 * <p>
	 * If {@link SslProvider#OPENSSL_REFCNT} is used then the object must be
	 * released. One way to do this is to wrap in a {@link SslHandler} and
	 * insert it into a pipeline. See {@link #newHandler(ByteBufAllocator)}.
	 * 
	 * @return a new {@link SSLEngine}
	 */
	public abstract SSLEngine newEngine();

	/**
	 * Creates a new {@link SSLEngine} using advisory peer information.
	 * <p>
	 * If {@link SslProvider#OPENSSL_REFCNT} is used then the object must be
	 * released. One way to do this is to wrap in a {@link SslHandler} and
	 * insert it into a pipeline. See
	 * {@link #newHandler(ByteBufAllocator, String, int)}.
	 * 
	 * @param peerHost
	 *            the non-authoritative name of the host
	 * @param peerPort
	 *            the non-authoritative port
	 *
	 * @return a new {@link SSLEngine}
	 */
	public abstract SSLEngine newEngine(String peerHost, int peerPort);

	/**
	 * Returns the {@link SSLSessionContext} object held by this context.
	 */
	public abstract SSLSessionContext sessionContext();

	/**
	 * Create a new SslHandler.
	 * 
	 * @see #newHandler(ByteBufAllocator)
	 */
	protected SslHandler newHandler( boolean startTls) {
		return new SslHandler(newEngine(), startTls);
	}

	/**
	 * Creates a new {@link SslHandler} with advisory peer information.
	 * <p>
	 * If {@link SslProvider#OPENSSL_REFCNT} is used then the returned
	 * {@link SslHandler} will release the engine that is wrapped. If the
	 * returned {@link SslHandler} is not inserted into a pipeline then you may
	 * leak native memory!
	 * <p>
	 * <b>Beware</b>: the underlying generated {@link SSLEngine} won't have
	 * <a href="https://wiki.openssl.org/index.php/Hostname_validation">hostname
	 * verification</a> enabled by default. If you create {@link SslHandler} for
	 * the client side and want proper security, we advice that you configure
	 * the {@link SSLEngine} (see
	 * {@link javax.net.ssl.SSLParameters#setEndpointIdentificationAlgorithm(String)}
	 * ):
	 * 
	 * <pre>
	 * SSLEngine sslEngine = sslHandler.engine();
	 * SSLParameters sslParameters = sslEngine.getSSLParameters();
	 * // only available since Java 7
	 * sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
	 * sslEngine.setSSLParameters(sslParameters);
	 * </pre>
	 * <p>
	 * The underlying {@link SSLEngine} may not follow the restrictions imposed
	 * by the <a href=
	 * "https://docs.oracle.com/javase/7/docs/api/javax/net/ssl/SSLEngine.html">
	 * SSLEngine javadocs</a> which limits wrap/unwrap to operate on a single
	 * SSL/TLS packet.
	 * 
	 * @param alloc
	 *            If supported by the SSLEngine then the SSLEngine will use this
	 *            to allocate ByteBuf objects.
	 * @param peerHost
	 *            the non-authoritative name of the host
	 * @param peerPort
	 *            the non-authoritative port
	 *
	 * @return a new {@link SslHandler}
	 */
	public final SslHandler newHandler(String peerHost, int peerPort) {
		return newHandler(peerHost, peerPort, startTls);
	}

	/**
	 * Create a new SslHandler.
	 * 
	 * @see #newHandler(ByteBufAllocator, String, int, boolean)
	 */
	protected SslHandler newHandler(String peerHost, int peerPort, boolean startTls) {
		return new SslHandler(newEngine(peerHost, peerPort), startTls);
	}

	/**
	 * Generates a key specification for an (encrypted) private key.
	 *
	 * @param password
	 *            characters, if {@code null} an unencrypted key is assumed
	 * @param key
	 *            bytes of the DER encoded private key
	 *
	 * @return a key specification
	 *
	 * @throws IOException
	 *             if parsing {@code key} fails
	 * @throws NoSuchAlgorithmException
	 *             if the algorithm used to encrypt {@code key} is unknown
	 * @throws NoSuchPaddingException
	 *             if the padding scheme specified in the decryption algorithm
	 *             is unknown
	 * @throws InvalidKeySpecException
	 *             if the decryption key based on {@code password} cannot be
	 *             generated
	 * @throws InvalidKeyException
	 *             if the decryption key based on {@code password} cannot be
	 *             used to decrypt {@code key}
	 * @throws InvalidAlgorithmParameterException
	 *             if decryption algorithm parameters are somehow faulty
	 */
	protected static PKCS8EncodedKeySpec generateKeySpec(char[] password, byte[] key) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException,
			InvalidKeySpecException, InvalidKeyException, InvalidAlgorithmParameterException {

		if (password == null) {
			return new PKCS8EncodedKeySpec(key);
		}

		EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(key);
		SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
		PBEKeySpec pbeKeySpec = new PBEKeySpec(password);
		SecretKey pbeKey = keyFactory.generateSecret(pbeKeySpec);

		Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
		cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedPrivateKeyInfo.getAlgParameters());

		return encryptedPrivateKeyInfo.getKeySpec(cipher);
	}

	/**
	 * Generates a new {@link KeyStore}.
	 *
	 * @param certChain
	 *            a X.509 certificate chain
	 * @param key
	 *            a PKCS#8 private key
	 * @param keyPasswordChars
	 *            the password of the {@code keyFile}. {@code null} if it's not
	 *            password-protected.
	 * @return generated {@link KeyStore}.
	 */
	static KeyStore buildKeyStore(X509Certificate[] certChain, PrivateKey key, char[] keyPasswordChars)
			throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(null, null);
		ks.setKeyEntry("key", key, keyPasswordChars, certChain);
		return ks;
	}

	static PrivateKey toPrivateKey(File keyFile, String keyPassword)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException, InvalidAlgorithmParameterException, KeyException, IOException {
		if (keyFile == null) {
			return null;
		}
		return getPrivateKeyFromByteBuffer(PemReader.readPrivateKey(keyFile), keyPassword);
	}
	
	static PrivateKey toPrivateKey(byte[] key, String keyPassword)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException, InvalidAlgorithmParameterException, KeyException, IOException {
		if (key == null) {
			return null;
		}
		return getPrivateKeyFromByteBuffer(PemReader.readPrivateKey(key), keyPassword);
	}

	static PrivateKey toPrivateKey(InputStream keyInputStream, String keyPassword)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException, InvalidAlgorithmParameterException, KeyException, IOException {
		if (keyInputStream == null) {
			return null;
		}
		return getPrivateKeyFromByteBuffer(PemReader.readPrivateKey(keyInputStream), keyPassword);
	}

	private static PrivateKey getPrivateKeyFromByteBuffer(byte[] key, String keyPassword)
			throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException, InvalidAlgorithmParameterException, KeyException, IOException {
		PKCS8EncodedKeySpec encodedKeySpec = generateKeySpec(keyPassword == null ? null : keyPassword.toCharArray(), key);
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(encodedKeySpec);
		} catch (InvalidKeySpecException ignore) {
			try {
				return KeyFactory.getInstance("DSA").generatePrivate(encodedKeySpec);
			} catch (InvalidKeySpecException ignore2) {
				try {
					return KeyFactory.getInstance("EC").generatePrivate(encodedKeySpec);
				} catch (InvalidKeySpecException e) {
					throw new InvalidKeySpecException("Neither RSA, DSA nor EC worked", e);
				}
			}
		}
	}


	@Deprecated
	protected static TrustManagerFactory buildTrustManagerFactory(File certChainFile, TrustManagerFactory trustManagerFactory)
			throws NoSuchAlgorithmException, CertificateException, KeyStoreException, IOException {
		X509Certificate[] x509Certs = toX509Certificates(certChainFile);

		return buildTrustManagerFactory(x509Certs, trustManagerFactory);
	}

	static X509Certificate[] toX509Certificates(File file) throws CertificateException {
		if (file == null) {
			return null;
		}
		return getCertificatesFromBuffers(PemReader.readCertificates(file));
	}

	static X509Certificate[] toX509Certificates(byte[] in) throws CertificateException {
		if (in == null) {
			return null;
		}
		return getCertificatesFromBuffers(PemReader.readCertificates(in));
	}

	static X509Certificate[] toX509Certificates(InputStream in) throws CertificateException {
		if (in == null) {
			return null;
		}
		return getCertificatesFromBuffers(PemReader.readCertificates(in));
	}

	private static X509Certificate[] getCertificatesFromBuffers(List<byte[]> certs) throws CertificateException {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate[] x509Certs = new X509Certificate[certs.size()];

		int i = 0;
		for (; i < certs.size(); i++) {
			InputStream is = new ByteArrayInputStream(certs.get(i));
			try {
				x509Certs[i] = (X509Certificate) cf.generateCertificate(is);
			} finally {
				try {
					is.close();
				} catch (IOException e) {
					// This is not expected to happen, but re-throw in case
					// it does.
					throw new RuntimeException(e);
				}
			}
		}

		return x509Certs;
	}

	static TrustManagerFactory buildTrustManagerFactory(X509Certificate[] certCollection, TrustManagerFactory trustManagerFactory)
			throws NoSuchAlgorithmException, CertificateException, KeyStoreException, IOException {
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(null, null);

		int i = 1;
		for (X509Certificate cert : certCollection) {
			String alias = Integer.toString(i);
			ks.setCertificateEntry(alias, cert);
			i++;
		}

		// Set up trust manager factory to use our key store.
		if (trustManagerFactory == null) {
			trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		}
		trustManagerFactory.init(ks);

		return trustManagerFactory;
	}

	static PrivateKey toPrivateKeyInternal(File keyFile, String keyPassword) throws SSLException {
		try {
			return toPrivateKey(keyFile, keyPassword);
		} catch (Exception e) {
			throw new SSLException(e);
		}
	}

	static X509Certificate[] toX509CertificatesInternal(File file) throws SSLException {
		try {
			return toX509Certificates(file);
		} catch (CertificateException e) {
			throw new SSLException(e);
		}
	}

	static KeyManagerFactory buildKeyManagerFactory(X509Certificate[] certChain, PrivateKey key, String keyPassword, KeyManagerFactory kmf)
			throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
		String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
		if (algorithm == null) {
			algorithm = "SunX509";
		}
		return buildKeyManagerFactory(certChain, algorithm, key, keyPassword, kmf);
	}

	static KeyManagerFactory buildKeyManagerFactory(X509Certificate[] certChainFile, String keyAlgorithm, PrivateKey key, String keyPassword,
			KeyManagerFactory kmf) throws KeyStoreException, NoSuchAlgorithmException, IOException, CertificateException, UnrecoverableKeyException {
		char[] keyPasswordChars = keyPassword == null ?ArrayUtil.EMPTY_CHARS : keyPassword.toCharArray();
		KeyStore ks = buildKeyStore(certChainFile, key, keyPasswordChars);
		// Set up key manager factory to use our key store
		if (kmf == null) {
			kmf = KeyManagerFactory.getInstance(keyAlgorithm);
		}
		kmf.init(ks, keyPasswordChars);

		return kmf;
	}
     static void addIfSupported(Set<String> supported, List<String> enabled, String... names) {
        for (String n: names) {
            if (supported.contains(n)) {
                enabled.add(n);
            }
        }
    }   static void useFallbackCiphersIfDefaultIsEmpty(List<String> defaultCiphers, String... fallbackCiphers) {
        useFallbackCiphersIfDefaultIsEmpty(defaultCiphers, asList(fallbackCiphers));
    } static void useFallbackCiphersIfDefaultIsEmpty(List<String> defaultCiphers, Iterable<String> fallbackCiphers) {
        if (defaultCiphers.isEmpty()) {
            for (String cipher : fallbackCiphers) {
                if (cipher.startsWith("SSL_") || cipher.contains("_RC4_")) {
                    continue;
                }
                defaultCiphers.add(cipher);
            }
        }
    }
	public enum ClientAuth {
	    /**
	     * Indicates that the {@link javax.net.ssl.SSLEngine} will not request client authentication.
	     */
	    NONE,

	    /**
	     * Indicates that the {@link javax.net.ssl.SSLEngine} will request client authentication.
	     */
	    OPTIONAL,

	    /**
	     * Indicates that the {@link javax.net.ssl.SSLEngine} will *require* client authentication.
	     */
	    REQUIRE
	}

}
