package dolmeangi.kotlin.common.network

import java.io.ByteArrayOutputStream

/**
 * Line buffer for accumulating bytes until complete CRLF-terminated lines are found
 *
 * This class buffers incoming bytes and scans for CRLF (\r\n) sequences,
 * returning complete lines while keeping partial data in the buffer.
 *
 * @param maxLineSize Maximum allowed line size in bytes
 * @throws LineTooLongException if a line exceeds maxLineSize
 */
class LineBuffer(private val maxLineSize: Int) {
    private val buffer = ByteArrayOutputStream()

    /**
     * Append bytes to the buffer and extract complete lines
     *
     * @param bytes Byte array to append
     * @param length Number of bytes to read from the array
     * @return List of complete lines (without CRLF)
     * @throws LineTooLongException if buffer size exceeds maxLineSize without finding CRLF
     */
    fun append(bytes: ByteArray, length: Int): List<String> {
        // Append new bytes to buffer
        buffer.write(bytes, 0, length)

        // Check for buffer overflow
        if (buffer.size() > maxLineSize) {
            throw LineTooLongException("Line exceeds maximum size of $maxLineSize bytes")
        }

        val lines = mutableListOf<String>()
        val bufferedBytes = buffer.toByteArray()

        var searchStart = 0
        while (searchStart < bufferedBytes.size - 1) {
            // Look for CRLF
            val crlfIndex = findCRLF(bufferedBytes, searchStart)

            if (crlfIndex == -1) {
                // No CRLF found, keep remaining bytes in buffer
                break
            }

            // Extract line (without CRLF)
            val line = String(bufferedBytes, searchStart, crlfIndex - searchStart, Charsets.UTF_8)
            lines.add(line)

            // Move past CRLF
            searchStart = crlfIndex + 2
        }

        // Keep remaining bytes in buffer
        if (searchStart > 0) {
            buffer.reset()
            if (searchStart < bufferedBytes.size) {
                buffer.write(bufferedBytes, searchStart, bufferedBytes.size - searchStart)
            }
        }

        return lines
    }

    /**
     * Find the index of the first CRLF sequence starting from offset
     *
     * @param bytes Byte array to search
     * @param offset Starting offset
     * @return Index of CR, or -1 if not found
     */
    private fun findCRLF(bytes: ByteArray, offset: Int): Int {
        for (i in offset until bytes.size - 1) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }

    /**
     * Reset the buffer, discarding all accumulated bytes
     */
    fun reset() {
        buffer.reset()
    }

    /**
     * Get current buffer size in bytes
     */
    fun size(): Int = buffer.size()
}

/**
 * Exception thrown when a line exceeds the maximum allowed size
 */
class LineTooLongException(message: String) : Exception(message)
