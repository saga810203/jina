package org.jfw.jina.ssl.engine;


import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.function.BiFunction;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.jfw.jina.ssl.JdkSslContext;
import org.jfw.jina.util.ArrayUtil;


public class Java9SslEngine extends JdkSslEngine {
	private static final Method SET_APPLICATION_PROTOCOLS;
	private static final Method GET_APPLICATION_PROTOCOL;
	private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL;
	private static final Method SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;
	private static final Method GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR;

	static {
		Method getHandshakeApplicationProtocol = null;
		Method getApplicationProtocol = null;
		Method setApplicationProtocols = null;
		Method setHandshakeApplicationProtocolSelector = null;
		Method getHandshakeApplicationProtocolSelector = null;

		try {
			SSLContext context = SSLContext.getInstance(JdkSslContext.PROTOCOL);
			context.init(null, null, null);
			SSLEngine engine = context.createSSLEngine();
			getHandshakeApplicationProtocol = AccessController.doPrivileged(new PrivilegedExceptionAction<Method>() {
				@Override
				public Method run() throws Exception {
					return SSLEngine.class.getMethod("getHandshakeApplicationProtocol");
				}
			});
			getHandshakeApplicationProtocol.invoke(engine);
			getApplicationProtocol = AccessController.doPrivileged(new PrivilegedExceptionAction<Method>() {
				@Override
				public Method run() throws Exception {
					return SSLEngine.class.getMethod("getApplicationProtocol");
				}
			});
			getApplicationProtocol.invoke(engine);

			setApplicationProtocols = AccessController.doPrivileged(new PrivilegedExceptionAction<Method>() {
				@Override
				public Method run() throws Exception {
					return SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
				}
			});
			setApplicationProtocols.invoke(engine.getSSLParameters(), new Object[] { ArrayUtil.EMPTY_STRINGS });

			setHandshakeApplicationProtocolSelector = AccessController.doPrivileged(new PrivilegedExceptionAction<Method>() {
				@Override
				public Method run() throws Exception {
					return SSLEngine.class.getMethod("setHandshakeApplicationProtocolSelector", BiFunction.class);
				}
			});
			setHandshakeApplicationProtocolSelector.invoke(engine, new BiFunction<SSLEngine, List<String>, String>() {
				@Override
				public String apply(SSLEngine sslEngine, List<String> strings) {
					return null;
				}
			});

			getHandshakeApplicationProtocolSelector = AccessController.doPrivileged(new PrivilegedExceptionAction<Method>() {
				@Override
				public Method run() throws Exception {
					return SSLEngine.class.getMethod("getHandshakeApplicationProtocolSelector");
				}
			});
			getHandshakeApplicationProtocolSelector.invoke(engine);
		} catch (Throwable t) {
			getHandshakeApplicationProtocol = null;
			getApplicationProtocol = null;
			setApplicationProtocols = null;
			setHandshakeApplicationProtocolSelector = null;
			getHandshakeApplicationProtocolSelector = null;
		}
		GET_HANDSHAKE_APPLICATION_PROTOCOL = getHandshakeApplicationProtocol;
		GET_APPLICATION_PROTOCOL = getApplicationProtocol;
		SET_APPLICATION_PROTOCOLS = setApplicationProtocols;
		SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = setHandshakeApplicationProtocolSelector;
		GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR = getHandshakeApplicationProtocolSelector;
	}





	public Java9SslEngine(SSLEngine engine, boolean isServer, String[] supportedProtocols) {
		super(engine,supportedProtocols);
		if (isServer) {
			try {
				SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine, new BiFunction<SSLEngine, List<String>, String>() {
					@Override
					public String apply(SSLEngine t, List<String> u) {
						assert !called;
						called = true;
						for (int i = 0; i < Java9SslEngine.this.supportedProtocols.length; ++i) {
							if (u.contains(Java9SslEngine.this.supportedProtocols[i])) {
								selectedProtocol = Java9SslEngine.this.supportedProtocols[i];
								return selectedProtocol;
							}
						}
						return null;
					}
				});
			} catch (UnsupportedOperationException ex) {
				throw ex;
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		} else {
			   SSLParameters parameters = engine.getSSLParameters();
		        try {
		            SET_APPLICATION_PROTOCOLS.invoke(parameters, new Object[]{supportedProtocols});
		        } catch (UnsupportedOperationException ex) {
		            throw ex;
		        } catch (Exception ex) {
		            throw new IllegalStateException(ex);
		        }
		        engine.setSSLParameters(parameters);
		}
	}


	
	
	
	




	public String getApplicationProtocol() {
		try {
			return (String) GET_APPLICATION_PROTOCOL.invoke(engine);
		} catch (UnsupportedOperationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public String getHandshakeApplicationProtocol() {
		try {
			return (String) GET_HANDSHAKE_APPLICATION_PROTOCOL.invoke(engine);
		} catch (UnsupportedOperationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public void setHandshakeApplicationProtocolSelector(BiFunction<SSLEngine, List<String>, String> selector) {
		try {
			SET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine, selector);
		} catch (UnsupportedOperationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	public BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector() {
		try {
			return (BiFunction<SSLEngine, List<String>, String>) GET_HANDSHAKE_APPLICATION_PROTOCOL_SELECTOR.invoke(engine);
		} catch (UnsupportedOperationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}




    @Override
    public SSLSession getSession() {
        return engine.getSession();
    }
    @Override
    public void closeInbound() throws SSLException {
        engine.closeInbound();
    }

    @Override
    public void closeOutbound() {
        engine.closeOutbound();
    }

    @Override
    public String getPeerHost() {
        return engine.getPeerHost();
    }

    @Override
    public int getPeerPort() {
        return engine.getPeerPort();
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer byteBuffer, ByteBuffer byteBuffer2) throws SSLException {
        return engine.wrap(byteBuffer, byteBuffer2);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] byteBuffers, ByteBuffer byteBuffer) throws SSLException {
        return engine.wrap(byteBuffers, byteBuffer);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] byteBuffers, int i, int i2, ByteBuffer byteBuffer) throws SSLException {
        return engine.wrap(byteBuffers, i, i2, byteBuffer);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer byteBuffer, ByteBuffer byteBuffer2) throws SSLException {
        return engine.unwrap(byteBuffer, byteBuffer2);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer byteBuffer, ByteBuffer[] byteBuffers) throws SSLException {
        return engine.unwrap(byteBuffer, byteBuffers);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer byteBuffer, ByteBuffer[] byteBuffers, int i, int i2) throws SSLException {
        return engine.unwrap(byteBuffer, byteBuffers, i, i2);
    }

    @Override
    public Runnable getDelegatedTask() {
        return engine.getDelegatedTask();
    }

    @Override
    public boolean isInboundDone() {
        return engine.isInboundDone();
    }

    @Override
    public boolean isOutboundDone() {
        return engine.isOutboundDone();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return engine.getSupportedCipherSuites();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return engine.getEnabledCipherSuites();
    }

    @Override
    public void setEnabledCipherSuites(String[] strings) {
        engine.setEnabledCipherSuites(strings);
    }

    @Override
    public String[] getSupportedProtocols() {
        return engine.getSupportedProtocols();
    }

    @Override
    public String[] getEnabledProtocols() {
        return engine.getEnabledProtocols();
    }

    @Override
    public void setEnabledProtocols(String[] strings) {
        engine.setEnabledProtocols(strings);
    }

    @Override
    public SSLSession getHandshakeSession() {
        return engine.getHandshakeSession();
    }

    @Override
    public void beginHandshake() throws SSLException {
        engine.beginHandshake();
    }

    @Override
    public HandshakeStatus getHandshakeStatus() {
        return engine.getHandshakeStatus();
    }

    @Override
    public void setUseClientMode(boolean b) {
        engine.setUseClientMode(b);
    }

    @Override
    public boolean getUseClientMode() {
        return engine.getUseClientMode();
    }

    @Override
    public void setNeedClientAuth(boolean b) {
        engine.setNeedClientAuth(b);
    }

    @Override
    public boolean getNeedClientAuth() {
        return engine.getNeedClientAuth();
    }

    @Override
    public void setWantClientAuth(boolean b) {
        engine.setWantClientAuth(b);
    }

    @Override
    public boolean getWantClientAuth() {
        return engine.getWantClientAuth();
    }

    @Override
    public void setEnableSessionCreation(boolean b) {
        engine.setEnableSessionCreation(b);
    }

    @Override
    public boolean getEnableSessionCreation() {
        return engine.getEnableSessionCreation();
    }

    @Override
    public SSLParameters getSSLParameters() {
        return engine.getSSLParameters();
    }

    @Override
    public void setSSLParameters(SSLParameters sslParameters) {
        engine.setSSLParameters(sslParameters);
    }
}
