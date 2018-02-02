package org.jfw.jina.ssl;

import javax.net.ssl.SSLHandshakeException;

public final class SslUtil {
	private SslUtil(){};
    public static SSLHandshakeException toSSLHandshakeException(Throwable e) {
        if (e instanceof SSLHandshakeException) {
            return (SSLHandshakeException) e;
        }

        return (SSLHandshakeException) new SSLHandshakeException(e.getMessage()).initCause(e);
    }
}
