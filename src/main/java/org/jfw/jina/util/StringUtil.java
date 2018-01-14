package org.jfw.jina.util;

public final class StringUtil {
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
}
