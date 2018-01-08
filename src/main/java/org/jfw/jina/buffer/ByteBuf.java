package org.jfw.jina.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;

public interface ByteBuf {
	/**
	 * Returns the number of bytes (octets) this buffer can contain.
	 */
	int capacity();
	/**
	 * Returns the {@code readerIndex} of this buffer.
	 */
	int readerIndex();
	/**
	 * Sets the {@code readerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code readerIndex} is less than {@code 0}
	 *             or greater than {@code this.writerIndex}
	 */
	ByteBuf readerIndex(int readerIndex);
	/**
	 * Returns the {@code writerIndex} of this buffer.
	 */
	int writerIndex();
	/**
	 * Sets the {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code writerIndex} is less than
	 *             {@code this.readerIndex} or greater than
	 *             {@code this.capacity}
	 */
	ByteBuf writerIndex(int writerIndex);

	/**
	 * Sets the {@code readerIndex} and {@code writerIndex} of this buffer in
	 * one shot. This method is useful when you have to worry about the
	 * invocation order of {@link #readerIndex(int)} and
	 * {@link #writerIndex(int)} methods. For example, the following code will
	 * fail:
	 *
	 * <pre>
	 * // Create a buffer whose readerIndex, writerIndex and capacity are
	 * // 0, 0 and 8 respectively.
	 * {@link ByteBuf} buf = {@link Unpooled}.buffer(8);
	 *
	 * // IndexOutOfBoundsException is thrown because the specified
	 * // readerIndex (2) cannot be greater than the current writerIndex (0).
	 * buf.readerIndex(2);
	 * buf.writerIndex(4);
	 * </pre>
	 *
	 * The following code will also fail:
	 *
	 * <pre>
	 * // Create a buffer whose readerIndex, writerIndex and capacity are
	 * // 0, 8 and 8 respectively.
	 * {@link ByteBuf} buf = {@link Unpooled}.wrappedBuffer(new byte[8]);
	 *
	 * // readerIndex becomes 8.
	 * buf.readLong();
	 *
	 * // IndexOutOfBoundsException is thrown because the specified
	 * // writerIndex (4) cannot be less than the current readerIndex (8).
	 * buf.writerIndex(4);
	 * buf.readerIndex(2);
	 * </pre>
	 *
	 * By contrast, this method guarantees that it never throws an
	 * {@link IndexOutOfBoundsException} as long as the specified indexes meet
	 * basic constraints, regardless what the current index values of the buffer
	 * are:
	 *
	 * <pre>
	 * // No matter what the current state of the buffer is, the following
	 * // call always succeeds as long as the capacity of the buffer is not
	 * // less than 4.
	 * buf.setIndex(2, 4);
	 * </pre>
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code readerIndex} is less than 0, if the
	 *             specified {@code writerIndex} is less than the specified
	 *             {@code readerIndex} or if the specified {@code writerIndex}
	 *             is greater than {@code this.capacity}
	 */
	ByteBuf setIndex(int readerIndex, int writerIndex);

	/**
	 * Returns the number of readable bytes which is equal to
	 * {@code (this.writerIndex - this.readerIndex)}.
	 */
	int readableBytes();

	/**
	 * Returns the number of writable bytes which is equal to
	 * {@code (this.capacity - this.writerIndex)}.
	 */
	int writableBytes();

	/**
	 * Returns {@code true} if and only if
	 * {@code (this.writerIndex - this.readerIndex)} is greater than {@code 0}.
	 */
	boolean isReadable();

	/**
	 * Returns {@code true} if and only if this buffer contains equal to or more
	 * than the specified number of elements.
	 */
	boolean isReadable(int size);

	/**
	 * Returns {@code true} if and only if
	 * {@code (this.capacity - this.writerIndex)} is greater than {@code 0}.
	 */
	boolean isWritable();

	/**
	 * Returns {@code true} if and only if this buffer has enough room to allow
	 * writing the specified number of elements.
	 */
	boolean isWritable(int size);

	/**
	 * Sets the {@code readerIndex} and {@code writerIndex} of this buffer to
	 * {@code 0}. This method is identical to {@link #setIndex(int, int)
	 * setIndex(0, 0)}.
	 * <p>
	 * Please note that the behavior of this method is different from that of
	 * NIO buffer, which sets the {@code limit} to the {@code capacity} of the
	 * buffer.
	 */
	ByteBuf clear();

	/**
	 * Discards the bytes between the 0th index and {@code readerIndex}. It
	 * moves the bytes between {@code readerIndex} and {@code writerIndex} to
	 * the 0th index, and sets {@code readerIndex} and {@code writerIndex} to
	 * {@code 0} and {@code oldWriterIndex - oldReaderIndex} respectively.
	 * <p>
	 * Please refer to the class documentation for more detailed explanation.
	 */
	ByteBuf discardReadBytes();

//	/**
//	 * Similar to {@link ByteBuf#discardReadBytes()} except that this method
//	 * might discard some, all, or none of read bytes depending on its internal
//	 * implementation to reduce overall memory bandwidth consumption at the cost
//	 * of potentially additional memory consumption.
//	 */
//	ByteBuf discardSomeReadBytes();

	/**
	 * Gets a boolean at the specified absolute (@code index) in this buffer.
	 * This method does not modify the {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 1} is greater than {@code this.capacity}
	 */
	boolean getBoolean(int index);

	/**
	 * Gets a boolean at the current {@code readerIndex} and increases the
	 * {@code readerIndex} by {@code 1} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 1} in
	 *             assertion
	 */
	boolean readBoolean();

	/**
	 * Gets a byte at the specified absolute {@code index} in this buffer. This
	 * method does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 1} is greater than {@code this.capacity}
	 */
	byte getByte(int index);

	/**
	 * Gets an unsigned byte at the specified absolute {@code index} in this
	 * buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 1} is greater than {@code this.capacity}
	 */
	short getUnsignedByte(int index);

	/**
	 * Gets a byte at the current {@code readerIndex} and increases the
	 * {@code readerIndex} by {@code 1} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 1}
	 */
	byte readByte();

	/**
	 * Gets an unsigned byte at the current {@code readerIndex} and increases
	 * the {@code readerIndex} by {@code 1} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 1}
	 */
	short readUnsignedByte();

	/**
	 * Gets a 16-bit short integer at the specified absolute {@code index} in
	 * this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	short getShort(int index);

	/**
	 * Gets a 16-bit short integer at the specified absolute {@code index} in
	 * this buffer in Little Endian Byte Order. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	short getShortLE(int index);

	/**
	 * Gets an unsigned 16-bit short integer at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	int getUnsignedShort(int index);

	/**
	 * Gets an unsigned 16-bit short integer at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	int getUnsignedShortLE(int index);

	/**
	 * Gets a 16-bit short integer at the current {@code readerIndex} and
	 * increases the {@code readerIndex} by {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	short readShort();

	/**
	 * Gets a 16-bit short integer at the current {@code readerIndex} in the
	 * Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	short readShortLE();

	/**
	 * Gets an unsigned 16-bit short integer at the current {@code readerIndex}
	 * and increases the {@code readerIndex} by {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	int readUnsignedShort();

	/**
	 * Gets an unsigned 16-bit short integer at the current {@code readerIndex}
	 * in the Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	int readUnsignedShortLE();

	/**
	 * Gets a 24-bit medium integer at the specified absolute {@code index} in
	 * this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	int getMedium(int index);

	/**
	 * Gets a 24-bit medium integer at the specified absolute {@code index} in
	 * this buffer in the Little Endian Byte Order. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	int getMediumLE(int index);

	/**
	 * Gets an unsigned 24-bit medium integer at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	int getUnsignedMedium(int index);

	/**
	 * Gets an unsigned 24-bit medium integer at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	int getUnsignedMediumLE(int index);

	/**
	 * Gets a 24-bit medium integer at the current {@code readerIndex} and
	 * increases the {@code readerIndex} by {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 3}
	 */
	int readMedium();

	/**
	 * Gets a 24-bit medium integer at the current {@code readerIndex} in the
	 * Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 3}
	 */
	int readMediumLE();

	/**
	 * Gets an unsigned 24-bit medium integer at the current {@code readerIndex}
	 * and increases the {@code readerIndex} by {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 3}
	 */
	int readUnsignedMedium();

	/**
	 * Gets an unsigned 24-bit medium integer at the current {@code readerIndex}
	 * in the Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 3}
	 */
	int readUnsignedMediumLE();

	/**
	 * Gets a 32-bit integer at the specified absolute {@code index} in this
	 * buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	int getInt(int index);

	/**
	 * Gets a 32-bit integer at the specified absolute {@code index} in this
	 * buffer with Little Endian Byte Order. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	int getIntLE(int index);

	/**
	 * Gets an unsigned 32-bit integer at the specified absolute {@code index}
	 * in this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	long getUnsignedInt(int index);

	/**
	 * Gets an unsigned 32-bit integer at the specified absolute {@code index}
	 * in this buffer in Little Endian Byte Order. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	long getUnsignedIntLE(int index);

	/**
	 * Gets a 32-bit integer at the current {@code readerIndex} and increases
	 * the {@code readerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	int readInt();

	/**
	 * Gets a 32-bit integer at the current {@code readerIndex} in the Little
	 * Endian Byte Order and increases the {@code readerIndex} by {@code 4} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	int readIntLE();

	/**
	 * Gets an unsigned 32-bit integer at the current {@code readerIndex} and
	 * increases the {@code readerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	long readUnsignedInt();

	/**
	 * Gets an unsigned 32-bit integer at the current {@code readerIndex} in the
	 * Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	long readUnsignedIntLE();

	/**
	 * Gets a 64-bit long integer at the specified absolute {@code index} in
	 * this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	long getLong(int index);

	/**
	 * Gets a 64-bit long integer at the specified absolute {@code index} in
	 * this buffer in Little Endian Byte Order. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	long getLongLE(int index);

	/**
	 * Gets a 64-bit integer at the current {@code readerIndex} and increases
	 * the {@code readerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 8}
	 */
	long readLong();

	/**
	 * Gets a 64-bit integer at the current {@code readerIndex} in the Little
	 * Endian Byte Order and increases the {@code readerIndex} by {@code 8} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 8}
	 */
	long readLongLE();

	/**
	 * Gets a 2-byte UTF-16 character at the specified absolute {@code index} in
	 * this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	char getChar(int index);

	/**
	 * Gets a 2-byte UTF-16 character at the current {@code readerIndex} and
	 * increases the {@code readerIndex} by {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	char readChar();

	/**
	 * Gets a 32-bit floating point number at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	float getFloat(int index);

	/**
	 * Gets a 32-bit floating point number at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	float getFloatLE(int index);
	// public float getFloatLE(int index) {
	// return Float.intBitsToFloat(getIntLE(index));
	// }

	/**
	 * Gets a 32-bit floating point number at the current {@code readerIndex}
	 * and increases the {@code readerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	float readFloat();

	/**
	 * Gets a 32-bit floating point number at the current {@code readerIndex} in
	 * Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 4}
	 */
	float readFloatLE();
	// public float readFloatLE() {
	// return Float.intBitsToFloat(readIntLE());
	// }

	/**
	 * Gets a 64-bit floating point number at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	double getDouble(int index);

	/**
	 * Gets a 64-bit floating point number at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	double getDoubleLE(int index);

	// public double getDoubleLE(int index) {
	// return Double.longBitsToDouble(getLongLE(index));
	// }
	/**
	 * Gets a 64-bit floating point number at the current {@code readerIndex}
	 * and increases the {@code readerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 8}
	 */
	double readDouble();

	/**
	 * Gets a 64-bit floating point number at the current {@code readerIndex} in
	 * Little Endian Byte Order and increases the {@code readerIndex} by
	 * {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 8}
	 */
	double readDoubleLE();
	// public double readDoubleLE() {
	// return Double.longBitsToDouble(readLongLE());
	// }



	/**
	 * Transfers this buffer's data to the specified destination starting at the
	 * specified absolute {@code index}. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or if
	 *             {@code index + dst.length} is greater than
	 *             {@code this.capacity}
	 */
	ByteBuf getBytes(int index, byte[] dst);

	/**
	 * Transfers this buffer's data to the specified destination starting at the
	 * current {@code readerIndex} and increases the {@code readerIndex} by the
	 * number of the transferred bytes (= {@code dst.length}).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code dst.length} is greater than
	 *             {@code this.readableBytes}
	 */
	ByteBuf readBytes(byte[] dst);

	/**
	 * Transfers this buffer's data to the specified destination starting at the
	 * specified absolute {@code index}. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @param dstIndex
	 *            the first index of the destination
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0}, if the
	 *             specified {@code dstIndex} is less than {@code 0}, if
	 *             {@code index + length} is greater than {@code this.capacity},
	 *             or if {@code dstIndex + length} is greater than
	 *             {@code dst.length}
	 */
	ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length);

	/**
	 * Transfers this buffer's data to the specified destination starting at the
	 * current {@code readerIndex} and increases the {@code readerIndex} by the
	 * number of the transferred bytes (= {@code length}).
	 *
	 * @param dstIndex
	 *            the first index of the destination
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code dstIndex} is less than {@code 0}, if
	 *             {@code length} is greater than {@code this.readableBytes}, or
	 *             if {@code dstIndex + length} is greater than
	 *             {@code dst.length}
	 */
	ByteBuf readBytes(byte[] dst, int dstIndex, int length);

	/**
	 * Gets a {@link CharSequence} with the given length at the given index.
	 *
	 * @param length
	 *            the length to read
	 * @param charset
	 *            that should be used
	 * @return the sequence
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	CharSequence getCharSequence(int index, int length, Charset charset);

	/**
	 * Sets the specified boolean at the specified absolute {@code index} in
	 * this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 1} is greater than {@code this.capacity}
	 */
	ByteBuf setBoolean(int index, boolean value);

	/**
	 * Sets the specified byte at the specified absolute {@code index} in this
	 * buffer. The 24 high-order bits of the specified value are ignored. This
	 * method does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 1} is greater than {@code this.capacity}
	 */
	ByteBuf setByte(int index, int value);

	/**
	 * Sets the specified 16-bit short integer at the specified absolute
	 * {@code index} in this buffer. The 16 high-order bits of the specified
	 * value are ignored. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	ByteBuf setShort(int index, int value);

	/**
	 * Sets the specified 16-bit short integer at the specified absolute
	 * {@code index} in this buffer with the Little Endian Byte Order. The 16
	 * high-order bits of the specified value are ignored. This method does not
	 * modify {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	ByteBuf setShortLE(int index, int value);

	/**
	 * Sets the specified 24-bit medium integer at the specified absolute
	 * {@code index} in this buffer. Please note that the most significant byte
	 * is ignored in the specified value. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	ByteBuf setMedium(int index, int value);

	/**
	 * Sets the specified 24-bit medium integer at the specified absolute
	 * {@code index} in this buffer in the Little Endian Byte Order. Please note
	 * that the most significant byte is ignored in the specified value. This
	 * method does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 3} is greater than {@code this.capacity}
	 */
	ByteBuf setMediumLE(int index, int value);

	/**
	 * Sets the specified 32-bit integer at the specified absolute {@code index}
	 * in this buffer. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	ByteBuf setInt(int index, int value);

	/**
	 * Sets the specified 32-bit integer at the specified absolute {@code index}
	 * in this buffer with Little Endian byte order . This method does not
	 * modify {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	ByteBuf setIntLE(int index, int value);

	/**
	 * Sets the specified 64-bit long integer at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	ByteBuf setLong(int index, long value);

	/**
	 * Sets the specified 64-bit long integer at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	ByteBuf setLongLE(int index, long value);

	/**
	 * Sets the specified 2-byte UTF-16 character at the specified absolute
	 * {@code index} in this buffer. The 16 high-order bits of the specified
	 * value are ignored. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 2} is greater than {@code this.capacity}
	 */
	ByteBuf setChar(int index, int value);

	/**
	 * Sets the specified 32-bit floating-point number at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	ByteBuf setFloat(int index, float value);

	/**
	 * Sets the specified 32-bit floating-point number at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 4} is greater than {@code this.capacity}
	 */
	ByteBuf setFloatLE(int index, float value);
	// public ByteBuf setFloatLE(int index, float value) {
	// return setIntLE(index, Float.floatToRawIntBits(value));
	// }

	/**
	 * Sets the specified 64-bit floating-point number at the specified absolute
	 * {@code index} in this buffer. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	ByteBuf setDouble(int index, double value);

	/**
	 * Sets the specified 64-bit floating-point number at the specified absolute
	 * {@code index} in this buffer in Little Endian Byte Order. This method
	 * does not modify {@code readerIndex} or {@code writerIndex} of this
	 * buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or
	 *             {@code index + 8} is greater than {@code this.capacity}
	 */
	ByteBuf setDoubleLE(int index, double value);
	// public ByteBuf setDoubleLE(int index, double value) {
	// return setLongLE(index, Double.doubleToRawLongBits(value));
	// }

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the specified absolute {@code index} until the source buffer becomes
	 * unreadable. This method is basically same with
	 * {@link #setBytes(int, ByteBuf, int, int)}, except that this method
	 * increases the {@code readerIndex} of the source buffer by the number of
	 * the transferred bytes while {@link #setBytes(int, ByteBuf, int, int)}
	 * does not. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of the source buffer (i.e. {@code this}).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or if
	 *             {@code index + src.readableBytes} is greater than
	 *             {@code this.capacity}
	 */
	ByteBuf setBytes(int index, ByteBuf src);

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the specified absolute {@code index}. This method is basically same with
	 * {@link #setBytes(int, ByteBuf, int, int)}, except that this method
	 * increases the {@code readerIndex} of the source buffer by the number of
	 * the transferred bytes while {@link #setBytes(int, ByteBuf, int, int)}
	 * does not. This method does not modify {@code readerIndex} or
	 * {@code writerIndex} of the source buffer (i.e. {@code this}).
	 *
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0}, if
	 *             {@code index + length} is greater than {@code this.capacity},
	 *             or if {@code length} is greater than
	 *             {@code src.readableBytes}
	 */
	ByteBuf setBytes(int index, ByteBuf src, int length);

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the specified absolute {@code index}. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of both the source (i.e.
	 * {@code this}) and the destination.
	 *
	 * @param srcIndex
	 *            the first index of the source
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0}, if the
	 *             specified {@code srcIndex} is less than {@code 0}, if
	 *             {@code index + length} is greater than {@code this.capacity},
	 *             or if {@code srcIndex + length} is greater than
	 *             {@code src.capacity}
	 */
	ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length);

	/**
	 * Transfers the specified source array's data to this buffer starting at
	 * the specified absolute {@code index}. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or if
	 *             {@code index + src.length} is greater than
	 *             {@code this.capacity}
	 */
	ByteBuf setBytes(int index, byte[] src);

	/**
	 * Transfers the specified source array's data to this buffer starting at
	 * the specified absolute {@code index}. This method does not modify
	 * {@code readerIndex} or {@code writerIndex} of this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0}, if the
	 *             specified {@code srcIndex} is less than {@code 0}, if
	 *             {@code index + length} is greater than {@code this.capacity},
	 *             or if {@code srcIndex + length} is greater than
	 *             {@code src.length}
	 */
	ByteBuf setBytes(int index, byte[] src, int srcIndex, int length);

	/**
	 * Fills this buffer with <tt>NUL (0x00)</tt> starting at the specified
	 * absolute {@code index}. This method does not modify {@code readerIndex}
	 * or {@code writerIndex} of this buffer.
	 *
	 * @param length
	 *            the number of <tt>NUL</tt>s to write to the buffer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code index} is less than {@code 0} or if
	 *             {@code index + length} is greater than {@code this.capacity}
	 */
	ByteBuf setZero(int index, int length);

	/**
	 * Writes the specified {@link CharSequence} at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by the written
	 * bytes.
	 *
	 * @param index
	 *            on which the sequence should be written
	 * @param sequence
	 *            to write
	 * @param charset
	 *            that should be used.
	 * @return the written number of bytes.
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is not large enough to write
	 *             the whole sequence
	 */
	int setCharSequence(int index, CharSequence sequence, Charset charset);

	/**
	 * Transfers this buffer's data to a newly created buffer starting at the
	 * current {@code readerIndex} and increases the {@code readerIndex} by the
	 * number of the transferred bytes (= {@code length}). The returned buffer's
	 * {@code readerIndex} and {@code writerIndex} are {@code 0} and
	 * {@code length} respectively.
	 *
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @return the newly created buffer which contains the transferred bytes
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	ByteBuf readBytes(int length);

	/**
	 * Transfers this buffer's data to a new inputStream starting at the current
	 * {@code readerIndex} and increases the {@code readerIndex} by the number
	 * of the transferred bytes (= {@code length}). The returned buffer's
	 * {@code readerIndex} and {@code writerIndex} are {@code 0} and
	 * {@code length} respectively.
	 *
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @return the newly created buffer which contains the transferred bytes
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	InputStream readAsInputStream(int length);

	/**
	 * Transfers this buffer's data to the specified stream starting at the
	 * current {@code readerIndex}.
	 *
	 *
	 * @return the actual number of bytes written out to the specified channel
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 * @throws IOException
	 *             if the specified channel threw an exception during I/O
	 */
	int readBytes(GatheringByteChannel out) throws IOException;

	/**
	 * Gets a {@link CharSequence} with the given length at the current
	 * {@code readerIndex} and increases the {@code readerIndex} by the given
	 * length.
	 *
	 * @param length
	 *            the length to read
	 * @param charset
	 *            that should be used
	 * @return the sequence
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	CharSequence readCharSequence(int length, Charset charset);

	/**
	 * Transfers this buffer's data starting at the current {@code readerIndex}
	 * to the specified channel starting at the given file position. This method
	 * does not modify the channel's position.
	 *
	 * @param position
	 *            the file position at which the transfer is to begin
	 * @param length
	 *            the maximum number of bytes to transfer
	 *
	 * @return the actual number of bytes written out to the specified channel
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 * @throws IOException
	 *             if the specified channel threw an exception during I/O
	 */
	int readBytes(FileChannel out, long position, int length) throws IOException;

	/**
	 * Increases the current {@code readerIndex} by the specified {@code length}
	 * in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	ByteBuf skipBytes(int length);

	/**
	 * Sets the specified boolean at the current {@code writerIndex} and
	 * increases the {@code writerIndex} by {@code 1} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 1}
	 */
	ByteBuf writeBoolean(boolean value);

	/**
	 * Sets the specified byte at the current {@code writerIndex} and increases
	 * the {@code writerIndex} by {@code 1} in this buffer. The 24 high-order
	 * bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 1}
	 */
	ByteBuf writeByte(int value);

	/**
	 * Sets the specified 16-bit short integer at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
	 * this buffer. The 16 high-order bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	ByteBuf writeShort(int value);

	/**
	 * Sets the specified 16-bit short integer in the Little Endian Byte Order
	 * at the current {@code writerIndex} and increases the {@code writerIndex}
	 * by {@code 2} in this buffer. The 16 high-order bits of the specified
	 * value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	ByteBuf writeShortLE(int value);

	/**
	 * Sets the specified 24-bit medium integer at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 3} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 3}
	 */
	ByteBuf writeMedium(int value);

	/**
	 * Sets the specified 24-bit medium integer at the current
	 * {@code writerIndex} in the Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 3}
	 */
	ByteBuf writeMediumLE(int value);

	/**
	 * Sets the specified 32-bit integer at the current {@code writerIndex} and
	 * increases the {@code writerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	ByteBuf writeInt(int value);

	/**
	 * Sets the specified 32-bit integer at the current {@code writerIndex} in
	 * the Little Endian Byte Order and increases the {@code writerIndex} by
	 * {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	ByteBuf writeIntLE(int value);

	/**
	 * Sets the specified 64-bit long integer at the current {@code writerIndex}
	 * and increases the {@code writerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	ByteBuf writeLong(long value);

	/**
	 * Sets the specified 64-bit long integer at the current {@code writerIndex}
	 * in the Little Endian Byte Order and increases the {@code writerIndex} by
	 * {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	ByteBuf writeLongLE(long value);

	/**
	 * Sets the specified 2-byte UTF-16 character at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
	 * this buffer. The 16 high-order bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	ByteBuf writeChar(int value);

	/**
	 * Sets the specified 32-bit floating point number at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 4} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	ByteBuf writeFloat(float value);

	/**
	 * Sets the specified 32-bit floating point number at the current
	 * {@code writerIndex} in Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	ByteBuf writeFloatLE(float value);
	// public ByteBuf writeFloatLE(float value) {
	// return writeIntLE(Float.floatToRawIntBits(value));
	// }

	/**
	 * Sets the specified 64-bit floating point number at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 8} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	ByteBuf writeDouble(double value);

	/**
	 * Sets the specified 64-bit floating point number at the current
	 * {@code writerIndex} in Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	ByteBuf writeDoubleLE(double value);
	// public ByteBuf writeDoubleLE(double value) {
	// return writeLongLE(Double.doubleToRawLongBits(value));
	// }

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the current {@code writerIndex} until the source buffer becomes
	 * unreadable, and increases the {@code writerIndex} by the number of the
	 * transferred bytes. This method is basically same with
	 * {@link #writeBytes(ByteBuf, int, int)}, except that this method increases
	 * the {@code readerIndex} of the source buffer by the number of the
	 * transferred bytes while {@link #writeBytes(ByteBuf, int, int)} does not.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code src.readableBytes} is greater than
	 *             {@code this.writableBytes}
	 */
	ByteBuf writeBytes(ByteBuf src);

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes (= {@code length}). This method is
	 * basically same with {@link #writeBytes(ByteBuf, int, int)}, except that
	 * this method increases the {@code readerIndex} of the source buffer by the
	 * number of the transferred bytes (= {@code length}) while
	 * {@link #writeBytes(ByteBuf, int, int)} does not.
	 *
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.writableBytes}
	 *             or if {@code length} is greater then
	 *             {@code src.readableBytes}
	 */
	ByteBuf writeBytes(ByteBuf src, int length);

	/**
	 * Transfers the specified source buffer's data to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes (= {@code length}).
	 *
	 * @param srcIndex
	 *            the first index of the source
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code srcIndex} is less than {@code 0}, if
	 *             {@code srcIndex + length} is greater than
	 *             {@code src.capacity}, or if {@code length} is greater than
	 *             {@code this.writableBytes}
	 */
	ByteBuf writeBytes(ByteBuf src, int srcIndex, int length);

	/**
	 * Transfers the specified source array's data to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes (= {@code src.length}).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code src.length} is greater than
	 *             {@code this.writableBytes}
	 */
	ByteBuf writeBytes(byte[] src);

	/**
	 * Transfers the specified source array's data to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes (= {@code length}).
	 *
	 * @param srcIndex
	 *            the first index of the source
	 * @param length
	 *            the number of bytes to transfer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if the specified {@code srcIndex} is less than {@code 0}, if
	 *             {@code srcIndex + length} is greater than {@code src.length},
	 *             or if {@code length} is greater than
	 *             {@code this.writableBytes}
	 */
	ByteBuf writeBytes(byte[] src, int srcIndex, int length);

	/**
	 * Transfers the content of the specified channel to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes.
	 *
	 * @param length
	 *            the maximum number of bytes to transfer
	 *
	 * @return the actual number of bytes read in from the specified channel
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.writableBytes}
	 * @throws IOException
	 *             if the specified channel threw an exception during I/O
	 */
	int writeBytes(ScatteringByteChannel in) throws IOException;

	/**
	 * Transfers the content of the specified channel starting at the given file
	 * position to this buffer starting at the current {@code writerIndex} and
	 * increases the {@code writerIndex} by the number of the transferred bytes.
	 * This method does not modify the channel's position.
	 *
	 * @param position
	 *            the file position at which the transfer is to begin
	 * @param length
	 *            the maximum number of bytes to transfer
	 *
	 * @return the actual number of bytes read in from the specified channel
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.writableBytes}
	 * @throws IOException
	 *             if the specified channel threw an exception during I/O
	 */
	int writeBytes(FileChannel in, long position, int length) throws IOException;

	/**
	 * Fills this buffer with <tt>NUL (0x00)</tt> starting at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by the
	 * specified {@code length}.
	 *
	 * @param length
	 *            the number of <tt>NUL</tt>s to write to the buffer
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.writableBytes}
	 */
	ByteBuf writeZero(int length);

	/**
	 * Writes the specified {@link CharSequence} at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by the written
	 * bytes. in this buffer.
	 *
	 * @param sequence
	 *            to write
	 * @param charset
	 *            that should be used
	 * @return the written number of bytes
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is not large enough to write
	 *             the whole sequence
	 */
	int writeCharSequence(CharSequence sequence, Charset charset);

	/**
	 * Locates the first occurrence of the specified {@code value} in this
	 * buffer. The search takes place from the specified {@code fromIndex}
	 * (inclusive) to the specified {@code toIndex} (exclusive).
	 * <p>
	 * If {@code fromIndex} is greater than {@code toIndex}, the search is
	 * performed in a reversed order.
	 * <p>
	 * This method does not modify {@code readerIndex} or {@code writerIndex} of
	 * this buffer.
	 *
	 * @return the absolute index of the first occurrence if found. {@code -1}
	 *         otherwise.
	 */
	int indexOf(int fromIndex, int toIndex, byte value);

	/**
	 * Locates the first occurrence of the specified {@code value} in this
	 * buffer. The search takes place from the current {@code readerIndex}
	 * (inclusive) to the current {@code writerIndex} (exclusive).
	 * <p>
	 * This method does not modify {@code readerIndex} or {@code writerIndex} of
	 * this buffer.
	 *
	 * @return the number of bytes between the current {@code readerIndex} and
	 *         the first occurrence if found. {@code -1} otherwise.
	 */
	int bytesBefore(byte value);

	/**
	 * Locates the first occurrence of the specified {@code value} in this
	 * buffer. The search starts from the current {@code readerIndex}
	 * (inclusive) and lasts for the specified {@code length}.
	 * <p>
	 * This method does not modify {@code readerIndex} or {@code writerIndex} of
	 * this buffer.
	 *
	 * @return the number of bytes between the current {@code readerIndex} and
	 *         the first occurrence if found. {@code -1} otherwise.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	int bytesBefore(int length, byte value);

	/**
	 * Locates the first occurrence of the specified {@code value} in this
	 * buffer. The search starts from the specified {@code index} (inclusive)
	 * and lasts for the specified {@code length}.
	 * <p>
	 * This method does not modify {@code readerIndex} or {@code writerIndex} of
	 * this buffer.
	 *
	 * @return the number of bytes between the specified {@code index} and the
	 *         first occurrence if found. {@code -1} otherwise.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code index + length} is greater than
	 *             {@code this.capacity}
	 */
	int bytesBefore(int index, int length, byte value);

//	/**
//	 * Returns a copy of this buffer's readable bytes. Modifying the content of
//	 * the returned buffer or this buffer does not affect each other at all.
//	 * This method is identical to
//	 * {@code buf.copy(buf.readerIndex(), buf.readableBytes())}. This method
//	 * does not modify {@code readerIndex} or {@code writerIndex} of this
//	 * buffer.
//	 */
//	ByteBuf copy();
//
//	/**
//	 * Returns a copy of this buffer's sub-region. Modifying the content of the
//	 * returned buffer or this buffer does not affect each other at all. This
//	 * method does not modify {@code readerIndex} or {@code writerIndex} of this
//	 * buffer.
//	 */
//	ByteBuf copy(int index, int length);
//
//	/**
//	 * Returns a slice of this buffer's readable bytes. Modifying the content of
//	 * the returned buffer or this buffer affects each other's content while
//	 * they maintain separate indexes and marks. This method is identical to
//	 * {@code buf.slice(buf.readerIndex(), buf.readableBytes())}. This method
//	 * does not modify {@code readerIndex} or {@code writerIndex} of this
//	 * buffer.
//	 * <p>
//	 * Also be aware that this method will NOT call {@link #retain()} and so the
//	 * reference count will NOT be increased.
//	 */
//	ByteBuf slice();
//
//	/**
//	 * Returns a retained slice of this buffer's readable bytes. Modifying the
//	 * content of the returned buffer or this buffer affects each other's
//	 * content while they maintain separate indexes and marks. This method is
//	 * identical to {@code buf.slice(buf.readerIndex(), buf.readableBytes())}.
//	 * This method does not modify {@code readerIndex} or {@code writerIndex} of
//	 * this buffer.
//	 * <p>
//	 * Note that this method returns a {@linkplain #retain() retained} buffer
//	 * unlike {@link #slice()}. This method behaves similarly to
//	 * {@code slice().retain()} except that this method may return a buffer
//	 * implementation that produces less garbage.
//	 */
//	ByteBuf retainedSlice();
//
//	/**
//	 * Returns a slice of this buffer's sub-region. Modifying the content of the
//	 * returned buffer or this buffer affects each other's content while they
//	 * maintain separate indexes and marks. This method does not modify
//	 * {@code readerIndex} or {@code writerIndex} of this buffer.
//	 * <p>
//	 * Also be aware that this method will NOT call {@link #retain()} and so the
//	 * reference count will NOT be increased.
//	 */
//	ByteBuf slice(int index, int length);
//
//	/**
//	 * Returns a retained slice of this buffer's sub-region. Modifying the
//	 * content of the returned buffer or this buffer affects each other's
//	 * content while they maintain separate indexes and marks. This method does
//	 * not modify {@code readerIndex} or {@code writerIndex} of this buffer.
//	 * <p>
//	 * Note that this method returns a {@linkplain #retain() retained} buffer
//	 * unlike {@link #slice(int, int)}. This method behaves similarly to
//	 * {@code slice(...).retain()} except that this method may return a buffer
//	 * implementation that produces less garbage.
//	 */
//	ByteBuf retainedSlice(int index, int length);
//
//	/**
//	 * Returns a buffer which shares the whole region of this buffer. Modifying
//	 * the content of the returned buffer or this buffer affects each other's
//	 * content while they maintain separate indexes and marks. This method does
//	 * not modify {@code readerIndex} or {@code writerIndex} of this buffer.
//	 * <p>
//	 * The reader and writer marks will not be duplicated. Also be aware that
//	 * this method will NOT call {@link #retain()} and so the reference count
//	 * will NOT be increased.
//	 * 
//	 * @return A buffer whose readable content is equivalent to the buffer
//	 *         returned by {@link #slice()}. However this buffer will share the
//	 *         capacity of the underlying buffer, and therefore allows access to
//	 *         all of the underlying content if necessary.
//	 */
//	ByteBuf duplicate();

	ByteBuf retain();

	void release();

}
