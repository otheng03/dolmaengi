package dolmeangi.kotlin.logserver.storage

import dolmeangi.kotlin.logserver.model.LogEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

/**
 * Represents a single log segment file
 *
 * File format:
 * [Header: 32 bytes]
 *   - Magic: 4 bytes (0xD0E1A96C)
 *   - Version: 2 bytes
 *   - Start Index: 8 bytes
 *   - Reserved: 18 bytes
 *
 * [Entry 1]
 *   - Length: 4 bytes (entry size)
 *   - CRC32: 4 bytes
 *   - Data: JSON serialized LogEntry
 *
 * [Entry 2]
 *   ...
 */
class LogSegment(
    private val path: Path,
    private val startIndex: Long
) : AutoCloseable {

    private val file: RandomAccessFile = RandomAccessFile(path.toFile(), "rw")
    private val channel: FileChannel = file.channel

    @Volatile
    private var entryCount = 0

    @Volatile
    private var lastIndex = startIndex - 1

    companion object {
        private const val MAGIC = 0x82245959L
        private const val VERSION: Short = 1
        private const val HEADER_SIZE = 32
        private const val RESERVED_SIZE = 18

        /**
         * Create a new log segment file
         */
        fun create(path: Path, startIndex: Long): LogSegment {
            val segment = LogSegment(path, startIndex)
            segment.writeHeader()
            logger.info { "Created new log segment: $path (start_index=$startIndex)" }
            return segment
        }

        /**
         * Open an existing log segment file
         */
        fun open(path: Path): LogSegment {
            if (!path.exists()) {
                throw IllegalArgumentException("Segment file does not exist: $path")
            }

            val file = RandomAccessFile(path.toFile(), "r")
            val header = ByteArray(HEADER_SIZE)
            file.read(header)

            val buffer = ByteBuffer.wrap(header)
            val magic = buffer.int.toUInt().toLong()
            val version = buffer.short
            val startIndex = buffer.long

            file.close()

            if (magic != MAGIC.toUInt().toLong()) {
                throw IllegalStateException("Invalid magic number in segment: $path")
            }

            if (version != VERSION) {
                throw IllegalStateException("Unsupported version $version in segment: $path")
            }

            val segment = LogSegment(path, startIndex)
            segment.scanEntries()
            logger.info { "Opened log segment: $path (start_index=$startIndex, entries=${segment.entryCount})" }
            return segment
        }
    }

    /**
     * Write the segment header
     */
    private fun writeHeader() {
        val buffer = ByteBuffer.allocate(HEADER_SIZE)
        buffer.putInt(MAGIC.toInt())
        buffer.putShort(VERSION)
        buffer.putLong(startIndex)
        // Reserved bytes are left as zeros
        for (i in 0 until RESERVED_SIZE ) {
            buffer.put(0)
        }
        buffer.flip()

        channel.position(0)
        channel.write(buffer)
        channel.force(true)
    }

    /**
     * Scan entries to rebuild entry count and last index
     */
    private fun scanEntries() {
        channel.position(HEADER_SIZE.toLong())
        var count = 0

        try {
            while (channel.position() < channel.size()) {
                val lengthBuffer = ByteBuffer.allocate(4)
                if (channel.read(lengthBuffer) != 4) break
                lengthBuffer.flip()
                val length = lengthBuffer.int

                if (length <= 0 || length > 10 * 1024 * 1024) break // Sanity check: max 10MB per entry

                // Skip CRC and data
                val newPosition = channel.position() + 4 + length
                if (newPosition > channel.size()) break

                channel.position(newPosition)
                count++
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error scanning entries, recovered $count entries" }
        }

        entryCount = count
        lastIndex = if (count > 0) startIndex + count - 1 else startIndex - 1

        logger.debug { "Scanned segment: entries=$entryCount, lastIndex=$lastIndex" }
    }

    /**
     * Append a log entry to this segment
     *
     * @return The file offset where the entry was written
     */
    fun append(entry: LogEntry): Long {
        val expectedIndex = lastIndex + 1
        if (entry.index != expectedIndex) {
            throw IllegalArgumentException(
                "Entry index ${entry.index} does not match expected index $expectedIndex"
            )
        }

        // Serialize entry
        val json = Json.encodeToString(entry)
        val data = json.toByteArray(Charsets.UTF_8)

        // Calculate CRC
        val crc = CRC32()
        crc.update(data)
        val checksum = crc.value.toInt()

        // Prepare buffer
        val buffer = ByteBuffer.allocate(8 + data.size)
        buffer.putInt(data.size)
        buffer.putInt(checksum)
        buffer.put(data)
        buffer.flip()

        // Write to file
        val offset = channel.size()
        channel.position(offset)
        channel.write(buffer)
        channel.force(true)

        // Update state
        lastIndex = entry.index
        entryCount++

        logger.debug { "Appended entry index=${entry.index} at offset=$offset (size=${data.size})" }

        return offset
    }

    /**
     * Read a log entry at the given file offset
     */
    fun read(offset: Long): LogEntry {
        val lengthBuffer = ByteBuffer.allocate(4)
        channel.position(offset)
        channel.read(lengthBuffer)
        lengthBuffer.flip()
        val length = lengthBuffer.int

        val buffer = ByteBuffer.allocate(4 + length)
        channel.read(buffer)
        buffer.flip()

        val storedChecksum = buffer.int
        val data = ByteArray(length)
        buffer.get(data)

        // Verify checksum
        val crc = CRC32()
        crc.update(data)
        val calculatedChecksum = crc.value.toInt()

        if (calculatedChecksum != storedChecksum) {
            throw IllegalStateException("Checksum mismatch at offset $offset")
        }

        // Deserialize
        val json = data.toString(Charsets.UTF_8)
        return Json.decodeFromString(json)
    }

    /**
     * Get the starting log index of this segment
     */
    fun getStartIndex(): Long = startIndex

    /**
     * Get the last log index in this segment
     */
    fun getLastIndex(): Long = lastIndex

    /**
     * Get the number of entries in this segment
     */
    fun getEntryCount(): Int = entryCount

    /**
     * Get the file size in bytes
     */
    fun getFileSize(): Long = channel.size()

    override fun close() {
        channel.close()
        file.close()
        logger.debug { "Closed log segment: $path" }
    }
}