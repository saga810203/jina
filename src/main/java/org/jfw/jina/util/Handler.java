package org.jfw.jina.util;

public interface Handler<I> {
	public Handler<Object> NOOP = new Handler<Object>(){
		@Override
		public void process(Object obj) {
		}};
	void process(I obj);
}
