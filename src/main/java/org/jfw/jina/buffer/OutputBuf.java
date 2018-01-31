package org.jfw.jina.buffer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.ScatteringByteChannel;

public interface OutputBuf {

	/**
	 * Returns {@code true} if and only if
	 * {@code (this.capacity - this.writerIndex)} is greater than {@code 0}.
	 */
	boolean writable();

	/**
	 * Returns {@code true} if and only if this buffer has enough room to allow
	 * writing the specified number of elements.
	 */
	boolean writable(int size);
	
	/**
	 * Returns the number of writable bytes which is equal to
	 * {@code (this.capacity - this.writerIndex)}.
	 */
	int writableBytes();
	/**
	 * Sets the specified boolean at the current {@code writerIndex} and
	 * increases the {@code writerIndex} by {@code 1} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 1}
	 */
	OutputBuf writeBoolean(boolean value);

	/**
	 * Sets the specified byte at the current {@code writerIndex} and increases
	 * the {@code writerIndex} by {@code 1} in this buffer. The 24 high-order
	 * bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 1}
	 */
	OutputBuf writeByte(int value);

	/**
	 * Sets the specified 16-bit short integer at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
	 * this buffer. The 16 high-order bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	OutputBuf writeShort(int value);

	/**
	 * Sets the specified 16-bit short integer in the Little Endian Byte Order
	 * at the current {@code writerIndex} and increases the {@code writerIndex}
	 * by {@code 2} in this buffer. The 16 high-order bits of the specified
	 * value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	OutputBuf writeShortLE(int value);

	/**
	 * Sets the specified 24-bit medium integer at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 3} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 3}
	 */
	OutputBuf writeMedium(int value);

	/**
	 * Sets the specified 24-bit medium integer at the current
	 * {@code writerIndex} in the Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 3} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 3}
	 */
	OutputBuf writeMediumLE(int value);

	/**
	 * Sets the specified 32-bit integer at the current {@code writerIndex} and
	 * increases the {@code writerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	OutputBuf writeInt(int value);

	/**
	 * Sets the specified 32-bit integer at the current {@code writerIndex} in
	 * the Little Endian Byte Order and increases the {@code writerIndex} by
	 * {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	OutputBuf writeIntLE(int value);

	/**
	 * Sets the specified 64-bit long integer at the current {@code writerIndex}
	 * and increases the {@code writerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	OutputBuf writeLong(long value);

	/**
	 * Sets the specified 64-bit long integer at the current {@code writerIndex}
	 * in the Little Endian Byte Order and increases the {@code writerIndex} by
	 * {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	OutputBuf writeLongLE(long value);

	/**
	 * Sets the specified 2-byte UTF-16 character at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 2} in
	 * this buffer. The 16 high-order bits of the specified value are ignored.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 2}
	 */
	OutputBuf writeChar(int value);

	/**
	 * Sets the specified 32-bit floating point number at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 4} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	OutputBuf writeFloat(float value);

	/**
	 * Sets the specified 32-bit floating point number at the current
	 * {@code writerIndex} in Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 4} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 4}
	 */
	OutputBuf writeFloatLE(float value);

	/**
	 * Sets the specified 64-bit floating point number at the current
	 * {@code writerIndex} and increases the {@code writerIndex} by {@code 8} in
	 * this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	OutputBuf writeDouble(double value);

	/**
	 * Sets the specified 64-bit floating point number at the current
	 * {@code writerIndex} in Little Endian Byte Order and increases the
	 * {@code writerIndex} by {@code 8} in this buffer.
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code this.writableBytes} is less than {@code 8}
	 */
	OutputBuf writeDoubleLE(double value);

	/**
	 * Transfers the specified source array's data to this buffer starting at
	 * the current {@code writerIndex} and increases the {@code writerIndex} by
	 * the number of the transferred bytes (= {@code src.length}).
	 *
	 * @throws IndexOutOfBoundsException
	 *             if {@code src.length} is greater than
	 *             {@code this.writableBytes}
	 */
	OutputBuf writeBytes(byte[] src);

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
	OutputBuf writeBytes(byte[] src, int srcIndex, int length);
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
    OutputBuf keepHead(int size); 
	OutputBuf retain();
	void release();
	InputBuf input();
	long size();
}
