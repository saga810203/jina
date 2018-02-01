package org.jfw.jina.ssl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface CipherSuiteFilter {
	public static final CipherSuiteFilter DEFAULT = new CipherSuiteFilter() {
		
		@Override
		public String[] filterCipherSuites(Iterable<String> ciphers, List<String> defaultCiphers, Set<String> supportedCiphers) {
	        final List<String> newCiphers;
	        if (ciphers == null) {
	            newCiphers = new ArrayList<String>(defaultCiphers.size());
	            ciphers = defaultCiphers;
	        } else {
	            newCiphers = new ArrayList<String>(supportedCiphers.size());
	        }
	        for (String c : ciphers) {
	            if (c == null) {
	                break;
	            }
	            if (supportedCiphers.contains(c)) {
	                newCiphers.add(c);
	            }
	        }
	        return newCiphers.toArray(new String[newCiphers.size()]);
		}
	};
	
	 String[] filterCipherSuites(Iterable<String> ciphers, List<String> defaultCiphers, Set<String> supportedCiphers);
}
