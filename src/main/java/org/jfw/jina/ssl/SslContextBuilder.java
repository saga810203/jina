package org.jfw.jina.ssl;


import java.io.File;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;



public class SslContextBuilder {
    public static SslContextBuilder forClient() {
        return new SslContextBuilder(false);
    }
    public static SslContextBuilder forServer(File keyCertChainFile, File keyFile) {
        return new SslContextBuilder(true).keyManager(keyCertChainFile, keyFile);
    }
    public static SslContextBuilder forServer(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return new SslContextBuilder(true).keyManager(keyCertChainInputStream, keyInputStream);
    }
    public static SslContextBuilder forServer(byte[] keyCertChainBuffer, byte[] keyBuffer) {
        return new SslContextBuilder(true).keyManager(keyCertChainBuffer, keyCertChainBuffer,null);
    }
    public static SslContextBuilder forServer(PrivateKey key, X509Certificate... keyCertChain) {
        return new SslContextBuilder(true).keyManager(key, keyCertChain);
    }
    public static SslContextBuilder forServer(
            File keyCertChainFile, File keyFile, String keyPassword) {
        return new SslContextBuilder(true).keyManager(keyCertChainFile, keyFile, keyPassword);
    }


    public static SslContextBuilder forServer(
            InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
        return new SslContextBuilder(true).keyManager(keyCertChainInputStream, keyInputStream, keyPassword);
    }
    public static SslContextBuilder forServer(
            byte[] keyCertChainInput, byte[] keyInput, String keyPassword) {
        return new SslContextBuilder(true).keyManager(keyCertChainInput , keyInput , keyPassword);
    }
    /**
     * Creates a builder for new server-side {@link SslContext}.
     *
     * @param key a PKCS#8 private key
     * @param keyCertChain the X.509 certificate chain
     * @param keyPassword the password of the {@code keyFile}, or {@code null} if it's not
     *     password-protected
     * @see #keyManager(File, File, String)
     */
    public static SslContextBuilder forServer(
            PrivateKey key, String keyPassword, X509Certificate... keyCertChain) {
        return new SslContextBuilder(true).keyManager(key, keyPassword, keyCertChain);
    }

    /**
     * Creates a builder for new server-side {@link SslContext}.
     *
     * @param keyManagerFactory non-{@code null} factory for server's private key
     * @see #keyManager(KeyManagerFactory)
     */
    public static SslContextBuilder forServer(KeyManagerFactory keyManagerFactory) {
        return new SslContextBuilder(true).keyManager(keyManagerFactory);
    }

    private final boolean forServer;
    private X509Certificate[] trustCertCollection;
    private TrustManagerFactory trustManagerFactory;
    private X509Certificate[] keyCertChain;
    private PrivateKey key;
    private String keyPassword;
    private KeyManagerFactory keyManagerFactory;
    private Iterable<String> ciphers;
    private CipherSuiteFilter cipherFilter =CipherSuiteFilter.DEFAULT;
    private long sessionCacheSize;
    private long sessionTimeout;
    private String[] protocols;
    private String[] applicationProtocols;
    private boolean startTls;

    private SslContextBuilder(boolean forServer) {
        this.forServer = forServer;
    }




    /**
     * Trusted certificates for verifying the remote endpoint's certificate. The file should
     * contain an X.509 certificate collection in PEM format. {@code null} uses the system default.
     */
    public SslContextBuilder trustManager(File trustCertCollectionFile) {
        try {
            return trustManager(SslContext.toX509Certificates(trustCertCollectionFile));
        } catch (Exception e) {
            throw new IllegalArgumentException("File does not contain valid certificates: "
                    + trustCertCollectionFile, e);
        }
    }

    public SslContextBuilder trustManager(InputStream trustCertCollectionInputStream) {
        try {
            return trustManager(SslContext.toX509Certificates(trustCertCollectionInputStream));
        } catch (Exception e) {
            throw new IllegalArgumentException("Input stream does not contain valid certificates.", e);
        }
    }

    public SslContextBuilder trustManager(X509Certificate... trustCertCollection) {
        this.trustCertCollection = trustCertCollection != null ? trustCertCollection.clone() : null;
        trustManagerFactory = null;
        return this;
    }

    public SslContextBuilder trustManager(TrustManagerFactory trustManagerFactory) {
        trustCertCollection = null;
        this.trustManagerFactory = trustManagerFactory;
        return this;
    }


    public SslContextBuilder keyManager(File keyCertChainFile, File keyFile) {
        return keyManager(keyCertChainFile, keyFile, null);
    }

    public SslContextBuilder keyManager(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return keyManager(keyCertChainInputStream, keyInputStream, null);
    }

    public SslContextBuilder keyManager(PrivateKey key, X509Certificate... keyCertChain) {
        return keyManager(key, null, keyCertChain);
    }


    public SslContextBuilder keyManager(File keyCertChainFile, File keyFile, String keyPassword) {
        X509Certificate[] keyCertChain;
        PrivateKey key;
        try {
            keyCertChain = SslContext.toX509Certificates(keyCertChainFile);
        } catch (Exception e) {
            throw new IllegalArgumentException("File does not contain valid certificates: " + keyCertChainFile, e);
        }
        try {
            key = SslContext.toPrivateKey(keyFile, keyPassword);
        } catch (Exception e) {
            throw new IllegalArgumentException("File does not contain valid private key: " + keyFile, e);
        }
        return keyManager(key, keyPassword, keyCertChain);
    }

    public SslContextBuilder keyManager(InputStream keyCertChainInputStream, InputStream keyInputStream,
            String keyPassword) {
        X509Certificate[] keyCertChain;
        PrivateKey key;
        try {
            keyCertChain = SslContext.toX509Certificates(keyCertChainInputStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Input stream not contain valid certificates.", e);
        }
        try {
            key = SslContext.toPrivateKey(keyInputStream, keyPassword);
        } catch (Exception e) {
            throw new IllegalArgumentException("Input stream does not contain valid private key.", e);
        }
        return keyManager(key, keyPassword, keyCertChain);
    }
    public SslContextBuilder keyManager(byte[] keyCertChainInputStream, byte[] keyInputStream,
            String keyPassword) {
        X509Certificate[] keyCertChain;
        PrivateKey key;
        try {
            keyCertChain = SslContext.toX509Certificates(keyCertChainInputStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Input stream not contain valid certificates.", e);
        }
        try {
            key = SslContext.toPrivateKey(keyInputStream, keyPassword);
        } catch (Exception e) {
            throw new IllegalArgumentException("Input stream does not contain valid private key.", e);
        }
        return keyManager(key, keyPassword, keyCertChain);
    }

    public SslContextBuilder keyManager(PrivateKey key, String keyPassword, X509Certificate... keyCertChain) {
        if (forServer) {

            if (keyCertChain.length == 0) {
                throw new IllegalArgumentException("keyCertChain must be non-empty");
            }

        }
        if (keyCertChain == null || keyCertChain.length == 0) {
            this.keyCertChain = null;
        } else {
            for (X509Certificate cert: keyCertChain) {
                if (cert == null) {
                    throw new IllegalArgumentException("keyCertChain contains null entry");
                }
            }
            this.keyCertChain = keyCertChain.clone();
        }
        this.key = key;
        this.keyPassword = keyPassword;
        keyManagerFactory = null;
        return this;
    }

    /**
     * Identifying manager for this host. {@code keyManagerFactory} may be {@code null} for
     * client contexts, which disables mutual authentication. Using a {@link KeyManagerFactory}
     * is only supported for {@link SslProvider#JDK} or {@link SslProvider#OPENSSL} / {@link SslProvider#OPENSSL_REFCNT}
     * if the used openssl version is 1.0.1+. You can check if your openssl version supports using a
     * {@link KeyManagerFactory} by calling {@link OpenSsl#supportsKeyManagerFactory()}. If this is not the case
     * you must use {@link #keyManager(File, File)} or {@link #keyManager(File, File, String)}.
     */
    public SslContextBuilder keyManager(KeyManagerFactory keyManagerFactory) {
        if (forServer) {
        	if(keyManagerFactory==null){
        		   throw new IllegalArgumentException("keyManagerFactory required for servers");
        	}
        }
        keyCertChain = null;
        key = null;
        keyPassword = null;
        this.keyManagerFactory = keyManagerFactory;
        return this;
    }


    public SslContextBuilder ciphers(Iterable<String> ciphers) {
        return ciphers(ciphers, CipherSuiteFilter.DEFAULT);
    }


    public SslContextBuilder ciphers(Iterable<String> ciphers, CipherSuiteFilter cipherFilter) {
        this.ciphers = ciphers;
        this.cipherFilter = cipherFilter;
        return this;
    }

    public SslContextBuilder sessionCacheSize(long sessionCacheSize) {
        this.sessionCacheSize = sessionCacheSize;
        return this;
    }

    public SslContextBuilder sessionTimeout(long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        return this;
    }



    /**
     * The TLS protocol versions to enable.
     * @param protocols The protocols to enable, or {@code null} to enable the default protocols.
     * @see SSLEngine#setEnabledCipherSuites(String[])
     */
    public SslContextBuilder sslProtocols(String... protocols) {
        this.protocols = protocols == null ? null : protocols.clone();
        return this;
    }
    public SslContextBuilder applicationProtocols(String... protocols){
    	this.applicationProtocols = protocols;
    	return this;
    }

    /**
     * {@code true} if the first write request shouldn't be encrypted.
     */
    public SslContextBuilder startTls(boolean startTls) {
        this.startTls = startTls;
        return this;
    }



    /**
     * Create new {@code SslContext} instance with configured settings.
     * <p>If {@link #sslProvider(SslProvider)} is set to {@link SslProvider#OPENSSL_REFCNT} then the caller is
     * responsible for releasing this object, or else native memory may leak.
     */
    public SslContext build() throws SSLException {
        if (forServer) {
        	return new JdkSslServerContext(trustCertCollection, trustManagerFactory, keyCertChain, key, keyPassword, keyManagerFactory,
					ciphers, cipherFilter,sessionCacheSize, sessionTimeout, protocols, startTls,applicationProtocols);
        } else {
        	return new JdkSslClientContext( trustCertCollection, trustManagerFactory, keyCertChain, key, keyPassword, keyManagerFactory, ciphers,
					cipherFilter,protocols, sessionCacheSize, sessionTimeout,applicationProtocols);
        }
    }
}
