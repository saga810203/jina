package org.jfw.jina.http;

public interface KeepAliveCheck {
	long getKeepAliveTime();
	void keepAliveTimeout();
}
