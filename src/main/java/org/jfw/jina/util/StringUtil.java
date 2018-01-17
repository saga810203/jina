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
	
	public static boolean equals(char[] src,int srcIndex,char[] dest,int destIndex, int length){
		for(int i =0;i< length;++i){
			if(src[srcIndex++] !=dest[destIndex++])
				return false;
		}
		return true;
	}
	
	public static String normalize(String path) {
		char[] chars = path.toCharArray();
		int len = chars.length;
		for (int i = 0; i < len; ++i) {
			if (chars[i] == '\\')
				chars[i] = '/';
		}
		if (chars[0] != '/') {
			char[] cc = new char[len + 1];
			cc[0] = '/';
			System.arraycopy(chars, 0, cc, 1, len);
			++len;
		}
		for (int i = 0; i < len; ++i) {
			if ('/' != chars[i])
				continue;
			if (i + 1 < len) {
				char c = chars[i + 1];
				if (c == '/') {
					System.arraycopy(chars, i + 2, chars, i + 1, len - i - 2);
					--len;
					--i;
					continue;
				} else if (c == '.' && (i + 2 < len)) {
					if (chars[i + 2] == '/') {
						System.arraycopy(chars, i + 3, chars, i + 1, len - i - 3);
						len -= 2;
						;
						--i;
						continue;
					} else if ((i + 3 < len) && ('.' == chars[i + 2]) && ('/' == chars[i + 3])) {
						int k = 0;
						for (int j = i - 1; j >= 0; --i) {
							if (chars[j] == '/') {
								k = j;
								break;
							}
						}
						System.arraycopy(chars, i + 4, chars, k + 1, len - i - 4);
						len = len - i - 3 + k; // len-i-4+k+1;
						i = k - 1;
					}
				}
			}
		}

		return new String(chars, 0, len);
	}
	public static String normalize(char[]path,int begin,int end) {
		char[] chars = new char[end-begin];
		System.arraycopy(path, begin,chars, 0,chars.length);		
		int len = chars.length;
		for (int i = 0; i < len; ++i) {
			if (chars[i] == '\\')
				chars[i] = '/';
		}
		if (chars[0] != '/') {
			return null;
		}
		for (int i = 0; i < len; ++i) {
			if ('/' != chars[i])
				continue;
			if (i + 1 < len) {
				char c = chars[i + 1];
				if (c == '/') {
					System.arraycopy(chars, i + 2, chars, i + 1, len - i - 2);
					--len;
					--i;
					continue;
				} else if (c == '.' && (i + 2 < len)) {
					if (chars[i + 2] == '/') {
						System.arraycopy(chars, i + 3, chars, i + 1, len - i - 3);
						len -= 2;
						;
						--i;
						continue;
					} else if ((i + 3 < len) && ('.' == chars[i + 2]) && ('/' == chars[i + 3])) {
						int k = 0;
						for (int j = i - 1; j >= 0; --i) {
							if (chars[j] == '/') {
								k = j;
								break;
							}
						}
						System.arraycopy(chars, i + 4, chars, k + 1, len - i - 4);
						len = len - i - 3 + k; // len-i-4+k+1;
						i = k - 1;
					}
				}
			}
		}
		return new String(chars, 0, len);
	}
}
