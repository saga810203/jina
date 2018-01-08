package org.jfw.jina.buffer;


public interface CompositeByteBuf extends ByteBuf {
    CompositeByteBuf addComponent(ByteBuf buffer);
    CompositeByteBuf addComponents(ByteBuf... buffers) ;
}
