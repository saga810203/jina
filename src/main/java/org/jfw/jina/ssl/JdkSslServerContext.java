package org.jfw.jina.ssl;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManagerFactory;

public class JdkSslServerContext extends JdkSslContext {
	JdkSslServerContext(X509Certificate[] trustCertCollection, TrustManagerFactory trustManagerFactory, X509Certificate[] keyCertChain, PrivateKey key,
			String keyPassword, KeyManagerFactory keyManagerFactory, Iterable<String> ciphers, CipherSuiteFilter cipherFilter, long sessionCacheSize,
			long sessionTimeout, String[] protocols, boolean startTls,String[] appcationProtocols) throws SSLException {
		super(newSSLContext(trustCertCollection, trustManagerFactory, keyCertChain, key, keyPassword, keyManagerFactory, sessionCacheSize,
				sessionTimeout), false, ciphers, cipherFilter,protocols, startTls,appcationProtocols);
	}

	private static SSLContext newSSLContext(X509Certificate[] trustCertCollection, TrustManagerFactory trustManagerFactory, X509Certificate[] keyCertChain,
			PrivateKey key, String keyPassword, KeyManagerFactory keyManagerFactory, long sessionCacheSize, long sessionTimeout) throws SSLException {
		if (key == null && keyManagerFactory == null) {
			throw new NullPointerException("key, keyManagerFactory");
		}

		try {
			if (trustCertCollection != null) {
				trustManagerFactory = buildTrustManagerFactory(trustCertCollection, trustManagerFactory);
			}
			if (key != null) {
				keyManagerFactory = buildKeyManagerFactory(keyCertChain, key, keyPassword, keyManagerFactory);
			}

			// Initialize the SSLContext to work with our key managers.
			SSLContext ctx =SSLContext.getInstance(PROTOCOL);
			ctx.init(keyManagerFactory.getKeyManagers(), trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), null);

			SSLSessionContext sessCtx = ctx.getServerSessionContext();
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
			throw new SSLException("failed to initialize the server-side SSL context", e);
		}
	}

}
