package org.jfw.jina.http2.headers;



public class HpackHeaderField {
    static final int HEADER_ENTRY_OVERHEAD = 32;
    static long sizeOf(String name, String value) {
        return name.length() + value.length() + HEADER_ENTRY_OVERHEAD;
    }
    final String name;
    final String value;
    HpackHeaderField(String name, String value) {
    	assert name!=null && value!=null;
        this.name =name;
        this.value = value;
    }
    final int size() {
        return name.length() + value.length() + HEADER_ENTRY_OVERHEAD;
    }
    @Override
    public final boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof HpackHeaderField)) {
            return false;
        }
        HpackHeaderField other = (HpackHeaderField) obj;
        return name.equals(other.name)  && value.equals(other.value);
    }
    @Override
    public String toString() {
        return name + ": " + value;
    }
}
