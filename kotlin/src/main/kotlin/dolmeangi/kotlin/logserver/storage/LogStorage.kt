package dolmeangi.kotlin.logserver.storage

import dolmeangi.kotlin.logserver.model.LogEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Main log storage manager
 *
 * Manages log segments and provides append/read operations.
 * Thread-safe for concurrent access.
 */
class LogStorage(
    private val dataDir: Path,
    private val maxSegmentSizeBytes: Long = 64 * 1024 * 1024,  // 64MB
    private val maxEntriesPerSegment: Int = 100_000
) : AutoCloseable {

    private val index = LogIndex()
    private val segments = mutableMapOf<Path, LogSegment>()
    private val mutex = Mutex()

    @Volatile
    private var currentSegment: LogSegment? = null

    @Volatile
    private var nextIndex: Long = 1

    init {
        // Create data directory if it doesn't exist
        if (!dataDir.exists()) {
            dataDir.createDirectories()
            logger.info { "Created data directory: $dataDir" }
        }

        // Load existing segments
        loadExistingSegments()
    }

    /**
     * Load existing log segments from disk
     */
    private fun loadExistingSegments() {
        val segmentFiles = dataDir.listDirectoryEntries("log-segment-*.log")
            .sorted()

        if (segmentFiles.isEmpty()) {
            logger.info { "No existing segments found, starting fresh" }
            return
        }

        logger.info { "Found ${segmentFiles.size} existing segments" }

        for (segmentFile in segmentFiles) {
            try {
                val segment = LogSegment.open(segmentFile)
                segments[segmentFile] = segment

                // Index all entries in this segment
                val startIndex = segment.getStartIndex()
                val entryCount = segment.getEntryCount()

                // Build index by scanning through the segment
                var offset = 32L // Skip header
                for (i in 0 until entryCount) {
                    val logIndex = startIndex + i
                    index.put(logIndex, segmentFile, offset)

                    // Read entry to get its size and move to next offset
                    val entry = segment.read(offset)
                    val json = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.serializer<LogEntry>(), entry
                    )
                    val dataSize = json.toByteArray(Charsets.UTF_8).size
                    offset += 8 + dataSize // 8 bytes for length+CRC, then data
                }

                logger.info { "Loaded segment: $segmentFile (entries=$entryCount)" }

                // Set as current segment if it's the last one and not full
                currentSegment = segment
                nextIndex = segment.getLastIndex() + 1

            } catch (e: Exception) {
                logger.error(e) { "Failed to load segment: $segmentFile" }
                throw e
            }
        }

        logger.info { "Loaded ${segments.size} segments, next index: $nextIndex" }
    }

    /**
     * Append a log entry
     *
     * @return The log index assigned to this entry
     */
    suspend fun append(
        transactionId: Long,
        commitTimestamp: Long,
        writes: List<dolmeangi.kotlin.logserver.model.WriteOperation>
    ): Long = mutex.withLock {
        // Get or create current segment BEFORE incrementing nextIndex
        var segment = getOrCreateCurrentSegment()

        // Check if we need to rotate segment
        if (shouldRotateSegment(segment)) {
            rotateSegment()
            segment = getOrCreateCurrentSegment()
        }

        // Now assign the log index and create entry
        val logIndex = nextIndex++

        val entry = LogEntry(
            index = logIndex,
            term = 1, // Single-node: always term 1
            transactionId = transactionId,
            commitTimestamp = commitTimestamp,
            writes = writes
        )

        // Append to segment
        val offset = segment.append(entry)
        val segmentPath = dataDir.resolve("log-segment-${segment.getStartIndex()}.log")
        index.put(entry.index, segmentPath, offset)

        logger.debug { "Appended log entry: index=$logIndex, txn=$transactionId" }

        return logIndex
    }

    /**
     * Check if segment should be rotated
     */
    private fun shouldRotateSegment(segment: LogSegment): Boolean {
        return segment.getFileSize() >= maxSegmentSizeBytes ||
                segment.getEntryCount() >= maxEntriesPerSegment
    }

    /**
     * Rotate to a new segment
     */
    private fun rotateSegment() {
        val oldSegment = currentSegment
        oldSegment?.close()

        val newStartIndex = nextIndex
        val newSegmentPath = dataDir.resolve("log-segment-$newStartIndex.log")
        currentSegment = LogSegment.create(newSegmentPath, newStartIndex)
        segments[newSegmentPath] = currentSegment!!

        logger.info { "Rotated to new segment: $newSegmentPath (start_index=$newStartIndex)" }
    }

    /**
     * Get or create the current segment
     */
    private fun getOrCreateCurrentSegment(): LogSegment {
        if (currentSegment == null) {
            // Use nextIndex as the starting index for the new segment
            val startIndex = nextIndex
            val segmentPath = dataDir.resolve("log-segment-$startIndex.log")
            currentSegment = LogSegment.create(segmentPath, startIndex)
            segments[segmentPath] = currentSegment!!
            logger.info { "Created initial segment: $segmentPath (startIndex=$startIndex)" }
        }
        return currentSegment!!
    }

    /**
     * Read a log entry by index
     */
    suspend fun read(logIndex: Long): LogEntry? = mutex.withLock {
        val indexEntry = index.get(logIndex) ?: return null
        val segment = segments[indexEntry.segmentPath] ?: return null
        return segment.read(indexEntry.fileOffset)
    }

    /**
     * Read log entries in the given range [startIndex, endIndex] inclusive
     */
    suspend fun readRange(startIndex: Long, endIndex: Long): List<LogEntry> = mutex.withLock {
        val entries = mutableListOf<LogEntry>()
        val indexEntries = index.getRange(startIndex, endIndex)

        for ((logIndex, indexEntry) in indexEntries) {
            val segment = segments[indexEntry.segmentPath]
            if (segment != null) {
                try {
                    val entry = segment.read(indexEntry.fileOffset)
                    entries.add(entry)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to read entry at index $logIndex" }
                }
            }
        }

        return entries
    }

    /**
     * Read all log entries from startIndex onwards
     */
    suspend fun readFrom(startIndex: Long): List<LogEntry> = mutex.withLock {
        val entries = mutableListOf<LogEntry>()
        val indexEntries = index.getFrom(startIndex)

        for ((logIndex, indexEntry) in indexEntries) {
            val segment = segments[indexEntry.segmentPath]
            if (segment != null) {
                try {
                    val entry = segment.read(indexEntry.fileOffset)
                    entries.add(entry)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to read entry at index $logIndex" }
                }
            }
        }

        return entries
    }

    /**
     * Get the last log index
     */
    fun getLastIndex(): Long? {
        return index.getLastIndex()
    }

    /**
     * Get the first log index
     */
    fun getFirstIndex(): Long? {
        return index.getFirstIndex()
    }

    /**
     * Check if storage is empty
     */
    fun isEmpty(): Boolean {
        return index.isEmpty()
    }

    override fun close() {
        segments.values.forEach { it.close() }
        segments.clear()
        currentSegment = null
        logger.info { "Closed log storage" }
    }
}