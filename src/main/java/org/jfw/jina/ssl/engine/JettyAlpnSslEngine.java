package org.jfw.jina.ssl.engine;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.alpn.ALPN;
import org.jfw.jina.util.StringUtil;

public class JettyAlpnSslEngine extends SSLEngine {

	protected SSLEngine engine;

	protected String selectedProtocol = StringUtil.EMPTY_STRING;
	protected boolean called = false;;

	static JettyAlpnSslEngine newClientEngine(SSLEngine engine, String[] protocols) {
		return new ClientEngine(engine, protocols);
	}

	static JettyAlpnSslEngine newServerEngine(SSLEngine engine, String[] protocols) {
		return new ServerEngine(engine, protocols);
	}

	private JettyAlpnSslEngine(SSLEngine engine) {
		this.engine = engine;
	}

	private static final class ClientEngine extends JettyAlpnSslEngine {
		ClientEngine(SSLEngine engine, final String[] protocols) {
			super(engine);
			ALPN.put(engine, new ALPN.ClientProvider() {
				@Override
				public List<String> protocols() {
					return Arrays.asList(protocols);
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

		@Override
		public void closeInbound() throws SSLException {
			try {
				ALPN.remove(getWrappedEngine());
			} finally {
				super.closeInbound();
			}
		}

		@Override
		public void closeOutbound() {
			try {
				ALPN.remove(getWrappedEngine());
			} finally {
				super.closeOutbound();
			}
		}
	}

	private static final class ServerEngine extends JettyAlpnSslEngine {
		ServerEngine(SSLEngine engine, final JdkApplicationProtocolNegotiator applicationNegotiator) {
			super(engine);
			checkNotNull(applicationNegotiator, "applicationNegotiator");
			final ProtocolSelector protocolSelector = checkNotNull(
					applicationNegotiator.protocolSelectorFactory().newSelector(this, new LinkedHashSet<String>(applicationNegotiator.protocols())),
					"protocolSelector");
			ALPN.put(engine, new ALPN.ServerProvider() {
				@Override
				public String select(List<String> protocols) throws SSLException {
					try {
						return protocolSelector.select(protocols);
					} catch (Throwable t) {
						throw toSSLHandshakeException(t);
					}
				}

				@Override
				public void unsupported() {
					protocolSelector.unsupported();
				}
			});
		}

		@Override
		public void closeInbound() throws SSLException {
			try {
				ALPN.remove(getWrappedEngine());
			} finally {
				super.closeInbound();
			}
		}

		@Override
		public void closeOutbound() {
			try {
				ALPN.remove(getWrappedEngine());
			} finally {
				super.closeOutbound();
			}
		}
	}
}
