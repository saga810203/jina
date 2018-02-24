package org.jfw.jina.ssl;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.NoSuchPaddingException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSessionContext;

import org.jfw.jina.ssl.engine.JdkSslEngine;
import org.jfw.jina.ssl.engine.JettyAlpnSslEngine;

public abstract class JdkSslContext extends SslContext {

	public static final String PROTOCOL = "TLS";
	private static final String[] DEFAULT_PROTOCOLS;
	private static final List<String> DEFAULT_CIPHERS;
	private static final Set<String> SUPPORTED_CIPHERS;

	static {
		SSLContext context;
		int i;
		try {
			context = SSLContext.getInstance(PROTOCOL);
			context.init(null, null, null);
		} catch (Exception e) {
			throw new Error("failed to initialize the default SSL context", e);
		}

		SSLEngine engine = context.createSSLEngine();

		// Choose the sensible default list of protocols.
		final String[] supportedProtocols = engine.getSupportedProtocols();
		Set<String> supportedProtocolsSet = new HashSet<String>(supportedProtocols.length);
		for (i = 0; i < supportedProtocols.length; ++i) {
			supportedProtocolsSet.add(supportedProtocols[i]);
		}
		List<String> protocols = new ArrayList<String>();
		addIfSupported(supportedProtocolsSet, protocols, "TLSv1.2", "TLSv1.1", "TLSv1");

		if (!protocols.isEmpty()) {
			DEFAULT_PROTOCOLS = protocols.toArray(new String[protocols.size()]);
		} else {
			DEFAULT_PROTOCOLS = engine.getEnabledProtocols();
		}

		final String[] supportedCiphers = engine.getSupportedCipherSuites();
		SUPPORTED_CIPHERS = new HashSet<String>(supportedCiphers.length);
		for (i = 0; i < supportedCiphers.length; ++i) {
			String supportedCipher = supportedCiphers[i];
			SUPPORTED_CIPHERS.add(supportedCipher);

			if (supportedCipher.startsWith("SSL_")) {
				SUPPORTED_CIPHERS.add("TLS_" + supportedCipher.substring("SSL_".length()));
			}
		}
		List<String> ciphers = new ArrayList<String>();
		addIfSupported(SUPPORTED_CIPHERS, ciphers, SslContext.DEFAULT_CIPHER_SUITES);
		useFallbackCiphersIfDefaultIsEmpty(ciphers, engine.getEnabledCipherSuites());
		DEFAULT_CIPHERS = Collections.unmodifiableList(ciphers);
	}

	private final String[] protocols;
	private final String[] appcationProtocols;
	private final String[] cipherSuites;
	private final List<String> unmodifiableCipherSuites;

	private final SSLContext sslContext;
	private final boolean isClient;

	public JdkSslContext(SSLContext sslContext, boolean isClient, Iterable<String> ciphers, CipherSuiteFilter cipherFilter, String[] protocols,
			String[] appcationProtocols) {
		super();
		cipherSuites = cipherFilter.filterCipherSuites(ciphers, DEFAULT_CIPHERS, SUPPORTED_CIPHERS);
		this.protocols = protocols == null ? DEFAULT_PROTOCOLS : protocols;
		unmodifiableCipherSuites = Collections.unmodifiableList(Arrays.asList(cipherSuites));
		this.sslContext = sslContext;
		this.isClient = isClient;
		this.appcationProtocols = appcationProtocols;
	}

	public final SSLContext context() {
		return sslContext;
	}

	@Override
	public final boolean isClient() {
		return isClient;
	}

	@Override
	public final SSLSessionContext sessionContext() {
		if (isServer()) {
			return context().getServerSessionContext();
		} else {
			return context().getClientSessionContext();
		}
	}

	@Override
	public final List<String> cipherSuites() {
		return unmodifiableCipherSuites;
	}

	@Override
	public final long sessionCacheSize() {
		return sessionContext().getSessionCacheSize();
	}

	@Override
	public final long sessionTimeout() {
		return sessionContext().getSessionTimeout();
	}

	@Override
	public final JdkSslEngine newEngine() {
		return wrapSSLEngine(context().createSSLEngine());
	}

	@Override
	public JdkSslEngine newEngine(String peerHost, int peerPort) {
		return wrapSSLEngine(context().createSSLEngine(peerHost, peerPort));
	}

	protected JdkSslEngine wrapSSLEngine(SSLEngine engine) {
		engine.setEnabledCipherSuites(cipherSuites);
		engine.setEnabledProtocols(protocols);
		boolean cliented = isClient();
		if (cliented) {
			engine.setUseClientMode(true);
		} else {
			engine.setUseClientMode(false);
			engine.setWantClientAuth(false);
		}
		// return new Java9SslEngine(engine, applicationNegotiator, isServer);
		return isClient ? JettyAlpnSslEngine.newClientEngine(engine, appcationProtocols) : JettyAlpnSslEngine.newServerEngine(engine, appcationProtocols);

	}

	/**
	 * Build a {@link KeyManagerFactory} based upon a key file, key file
	 * password, and a certificate chain.
	 * 
	 * @param certChainFile
	 *            a X.509 certificate chain file in PEM format
	 * @param keyFile
	 *            a PKCS#8 private key file in PEM format
	 * @param keyPassword
	 *            the password of the {@code keyFile}. {@code null} if it's not
	 *            password-protected.
	 * @param kmf
	 *            The existing {@link KeyManagerFactory} that will be used if
	 *            not {@code null}
	 * @return A {@link KeyManagerFactory} based upon a key file, key file
	 *         password, and a certificate chain.
	 * @deprecated will be removed.
	 */
	@Deprecated
	protected static KeyManagerFactory buildKeyManagerFactory(File certChainFile, File keyFile, String keyPassword, KeyManagerFactory kmf)
			throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException,
			InvalidAlgorithmParameterException, CertificateException, KeyException, IOException {
		String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
		if (algorithm == null) {
			algorithm = "SunX509";
		}
		return buildKeyManagerFactory(certChainFile, algorithm, keyFile, keyPassword, kmf);
	}

	/**
	 * Build a {@link KeyManagerFactory} based upon a key algorithm, key file,
	 * key file password, and a certificate chain.
	 * 
	 * @param certChainFile
	 *            a X.509 certificate chain file in PEM format
	 * @param keyAlgorithm
	 *            the standard name of the requested algorithm. See the Java
	 *            Secure Socket Extension Reference Guide for information about
	 *            standard algorithm names.
	 * @param keyFile
	 *            a PKCS#8 private key file in PEM format
	 * @param keyPassword
	 *            the password of the {@code keyFile}. {@code null} if it's not
	 *            password-protected.
	 * @param kmf
	 *            The existing {@link KeyManagerFactory} that will be used if
	 *            not {@code null}
	 * @return A {@link KeyManagerFactory} based upon a key algorithm, key file,
	 *         key file password, and a certificate chain.
	 * @deprecated will be removed.
	 */
	@Deprecated
	protected static KeyManagerFactory buildKeyManagerFactory(File certChainFile, String keyAlgorithm, File keyFile, String keyPassword, KeyManagerFactory kmf)
			throws KeyStoreException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException, InvalidAlgorithmParameterException,
			IOException, CertificateException, KeyException, UnrecoverableKeyException {
		return buildKeyManagerFactory(toX509Certificates(certChainFile), keyAlgorithm, toPrivateKey(keyFile, keyPassword), keyPassword, kmf);
	}
}
