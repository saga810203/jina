package org.jfw.jina.ssl.engine;

import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.alpn.ALPN;
import org.jfw.jina.ssl.SslUtil;

public class JettyAlpnSslEngine extends JdkSslEngine {

	public static JdkSslEngine newClientEngine(SSLEngine engine, String[] protocols) {
		return new ClientEngine(engine, protocols);
	}

	public static JdkSslEngine newServerEngine(SSLEngine engine, String[] protocols) {
		return new ServerEngine(engine, protocols);
	}

	private JettyAlpnSslEngine(SSLEngine engine,String[] supportedProtocols) {
		super(engine,supportedProtocols);
	}

	private static final class ClientEngine extends JettyAlpnSslEngine {
		ClientEngine(SSLEngine engine, String[] supportedProtocols) {
			super(engine,supportedProtocols);
			ALPN.put(engine, new ALPN.ClientProvider() {
				@Override
				public List<String> protocols() {
					return Arrays.asList(ClientEngine.this.supportedProtocols);
				}

				@Override
				public void selected(String protocol) throws SSLException {
					called = true;
					selectedProtocol = protocol;
				}

				@Override
				public void unsupported() {
					called = true;
				}
			});
		}

	}

	private static final class ServerEngine extends JettyAlpnSslEngine {
		ServerEngine(SSLEngine engine,  String[] supportedProtocols) {
			super(engine,supportedProtocols);
			ALPN.put(engine, new ALPN.ServerProvider() {
				@Override
				public String select(List<String> protocols) throws SSLException {
					called = true;
					try {
						for (int i = 0; i <ServerEngine.this. supportedProtocols.length; ++i) {
							if (protocols.contains(ServerEngine.this. supportedProtocols[i])) {
								selectedProtocol = ServerEngine.this. supportedProtocols[i];
								return selectedProtocol;
							}
						}
						return null;
					} catch (Throwable t) {
						throw SslUtil.toSSLHandshakeException(t);
					}
				}
				@Override
				public void unsupported() {
					called = true;
				}
			});
		}

	}

	@Override
	public void closeInbound() throws SSLException {
		try {
			ALPN.remove(engine);
		} finally {
			engine.closeInbound();
		}
	}

	@Override
	public void closeOutbound() {
		try {
			ALPN.remove(engine);
		} finally {
			engine.closeOutbound();
		}
	}


}
