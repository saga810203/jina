package org.jfw.jina.util;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public final class StringUtil {
	public static final Charset UTF8=Charset.forName("UTF-8");
	private StringUtil(){}
	public static final String EMPTY_STRING="";
	
	public static int findNonWhitespace(char[] seq,int begin,int end) {
		for (int result = begin; result < end; ++result) {
			if (!Character.isWhitespace(seq[result])) {
				return result;
			}
		}
		return end;
	}

	public static int findWhitespace(char[] seq,int begin,int end ){
		for (int result = begin; result < end; ++result) {
			if (Character.isWhitespace(seq[result])) {
				return result;
			}
		}
		return end;
	}

	public static int findEndOfString(char[] seq,int begin,int end) {
		for (int result = end - 1; result > begin; --result) {
			if (!Character.isWhitespace(seq[result])) {
				return result + 1;
			}
		}
		return begin;
	}
	
	public static String trim(char[] val,int begin,int end){
		assert val.length>=end;
          while ((begin < end) && (val[begin] <= ' ')) {
            begin++;
        }
        while ((begin < end) && (val[end - 1] <= ' ')) {
            end--;
        }
        return end==begin ?StringUtil.EMPTY_STRING :new String(val,begin,end-begin);
	}
	
	public static String urlUTF8Encoding(String src) throws UnsupportedEncodingException{
		return  java.net.URLEncoder.encode(src,"UTF-8");
	}
	public static String urlUTF8Decoding(String src) throws UnsupportedEncodingException{
		return  java.net.URLDecoder.decode(src,"UTF-8");
	}
	
	public static byte[] utf8(String src){
		return src.getBytes(UTF8);
	}
}
