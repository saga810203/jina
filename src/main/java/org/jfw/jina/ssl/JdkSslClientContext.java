package org.jfw.jina.ssl;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;

public class JdkSslClientContext extends JdkSslContext {

	JdkSslClientContext(File trustCertCollectionFile, TrustManagerFactory trustManagerFactory, Iterable<String> ciphers, CipherSuiteFilter cipherFilter,
			long sessionCacheSize, long sessionTimeout, String[] applicationProtocols) throws SSLException {
		super(newSSLContext(toX509CertificatesInternal(trustCertCollectionFile), trustManagerFactory, null, null, null, null, sessionCacheSize, sessionTimeout),
				true, ciphers, cipherFilter, null, false, applicationProtocols);
	}

	JdkSslClientContext(X509Certificate[] trustCertCollection, TrustManagerFactory trustManagerFactory, X509Certificate[] keyCertChain, PrivateKey key,
			String keyPassword, KeyManagerFactory keyManagerFactory, Iterable<String> ciphers, CipherSuiteFilter cipherFilter, String[] protocols,
			long sessionCacheSize, long sessionTimeout, String[] applicationProtocols) throws SSLException {
		super(newSSLContext(trustCertCollection, trustManagerFactory, keyCertChain, key, keyPassword, keyManagerFactory, sessionCacheSize, sessionTimeout),
				true, ciphers, cipherFilter, protocols, false, applicationProtocols);
	}

	private static SSLContext newSSLContext(X509Certificate[] trustCertCollection, TrustManagerFactory trustManagerFactory, X509Certificate[] keyCertChain,
			PrivateKey key, String keyPassword, KeyManagerFactory keyManagerFactory, long sessionCacheSize, long sessionTimeout) throws SSLException {
		try {
			if (trustCertCollection != null) {
				trustManagerFactory = buildTrustManagerFactory(trustCertCollection, trustManagerFactory);
			}
			if (keyCertChain != null) {
				keyManagerFactory = buildKeyManagerFactory(keyCertChain, key, keyPassword, keyManagerFactory);
			}
			SSLContext ctx = SSLContext.getInstance(PROTOCOL);
			ctx.init(keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
					trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), null);

			SSLSessionContext sessCtx = ctx.getClientSessionContext();
			if (sessionCacheSize > 0) {
				sessCtx.setSessionCacheSize((int) Math.min(sessionCacheSize, Integer.MAX_VALUE));
			}
			if (sessionTimeout > 0) {
				sessCtx.setSessionTimeout((int) Math.min(sessionTimeout, Integer.MAX_VALUE));
			}
			return ctx;
		} catch (Exception e) {
			if (e instanceof SSLException) {
				throw (SSLException) e;
			}
			throw new SSLException("failed to initialize the client-side SSL context", e);
		}
	}
}
