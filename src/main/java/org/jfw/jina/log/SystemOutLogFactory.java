package org.jfw.jina.log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

import org.jfw.jina.util.StringUtil;

public class SystemOutLogFactory extends LogFactory {
	private static final List<String> VALID_CLASSES =new LinkedList<String>();
	static {
		try {
			Enumeration<URL> en = Thread.currentThread().getContextClassLoader().getResources("SystemOut.config");
			while (en.hasMoreElements()) {
				URL url = en.nextElement();
				URLConnection con = url.openConnection();
				InputStream in = con.getInputStream();
				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(in, StringUtil.US_ASCII));
					String line = null;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.length() > 0 && (!line.startsWith("#"))) {
							VALID_CLASSES.add(line);
						}
					}
				} finally {
					try {
						in.close();
					} catch (IOException e) {
					}
				}
			}

		} catch (Throwable t) {
		}

	}

	@Override
	public Logger getLogger(Class<?> clazz) {
		if (VALID_CLASSES.contains(clazz.getName())) {
			return SystemOutLog.INS;
		} else {
			return NoLogger.INS;
		}
	}

}
