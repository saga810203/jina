package org.jfw.jina.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;

public interface InputBuf {
	BufAllocator alloc();
	
	/**
	 * Returns the number of readable bytes which is equal to
	 * {@code (this.writerIndex - this.readerIndex)}.
	 */
	int readableBytes();
	/**
	 * Returns {@code true} if and only if
	 * {@code (this.writerIndex - this.readerIndex)} is greater than {@code 0}.
	 */
	boolean readable();

	/**
	 * Returns {@code true} if and only if this buffer contains equal to or more
	 * than the specified number of elements.
	 */
	boolean readable(int size);
	
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
	 * Gets a 2-byte UTF-16 character at the current {@code readerIndex} and
	 * increases the {@code readerIndex} by {@code 2} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.readableBytes} is less than {@code 2}
	 */
	char readChar();
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
	/**
	 * Transfers this buffer's data to the specified destination starting at the
	 * current {@code readerIndex} and increases the {@code readerIndex} by the
	 * number of the transferred bytes (= {@code dst.length}).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code dst.length} is greater than
	 *             {@code this.readableBytes}
	 */
	InputBuf readBytes(byte[] dst);
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
	InputBuf readBytes(byte[] dst, int dstIndex, int length);


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
	int readBytes(FileChannel out, long position) throws IOException;

	/**
	 * Increases the current {@code readerIndex} by the specified {@code length}
	 * in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code length} is greater than {@code this.readableBytes}
	 */
	InputBuf skipBytes(int length);
	InputBuf skipAllBytes();
	
	
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
	int indexOf(byte value);
	InputBuf retain();
	void release();
	InputBuf duplicate();
	InputBuf duplicate(int length);
	InputBuf slice();
	
	
	boolean skipControlCharacters();
	
    /**
     * Iterates over the readable bytes of this buffer with the specified {@code processor} in ascending order.
     *
     * @return {@code -1} if the processor iterated to or beyond the end of the readable bytes.
     *         The last-visited index If the {@link ByteProcessor#process(byte)} returned {@code false}.
     */
    int forEachByte(ByteProcessor processor);
}
