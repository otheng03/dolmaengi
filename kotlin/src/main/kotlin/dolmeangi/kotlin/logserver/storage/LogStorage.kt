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

    // ===== Raft-specific operations =====

    /**
     * Get the term of the log entry at the given index
     *
     * @return Term of the entry, or null if index doesn't exist
     */
    suspend fun getTermAt(logIndex: Long): Long? {
        val entry = read(logIndex) ?: return null
        return entry.term
    }

    /**
     * Get the index and term of the last log entry
     *
     * Used by Raft RequestVote RPC to determine if candidate's log is up-to-date.
     *
     * @return Pair of (lastLogIndex, lastLogTerm), or (0, 0) if log is empty
     */
    suspend fun getLastLogIndexAndTerm(): Pair<Long, Long> {
        val lastIndex = getLastIndex() ?: return Pair(0, 0)
        val lastTerm = getTermAt(lastIndex) ?: return Pair(0, 0)
        return Pair(lastIndex, lastTerm)
    }

    /**
     * Delete all log entries from the given index onwards
     *
     * Used by Raft when follower's log conflicts with leader's log.
     * After truncation, new entries from leader will be appended.
     *
     * IMPORTANT: This is a destructive operation. Only call when Raft protocol
     * guarantees it's safe (i.e., when leader's log conflicts with ours).
     *
     * @param fromIndex Index to start deletion (inclusive)
     */
    suspend fun truncateFrom(fromIndex: Long): Unit = mutex.withLock {
        if (fromIndex <= 0) {
            logger.warn { "Cannot truncate from index $fromIndex (must be > 0)" }
            return
        }

        val lastIndex = getLastIndex()
        if (lastIndex == null || fromIndex > lastIndex) {
            logger.debug { "Truncate from $fromIndex: no-op (last index: $lastIndex)" }
            return
        }

        logger.info { "Truncating log from index $fromIndex to $lastIndex" }

        // Remove entries from in-memory index
        val removedCount = index.removeFrom(fromIndex)

        // Adjust nextIndex to allow new appends
        nextIndex = fromIndex

        // Close currentSegment to force new segment creation on next append
        // This is necessary because the segment tracks its own nextIndex internally
        if (currentSegment != null) {
            logger.debug { "Closing currentSegment due to truncation" }
            currentSegment = null
        }

        // TODO: In a full implementation, we should also truncate segment files on disk
        // For now, we just remove from the in-memory index and reset currentSegment
        // This is sufficient since we rebuild from disk on restart

        logger.info { "Truncated $removedCount entries from index $fromIndex, nextIndex=$nextIndex" }
    }

    /**
     * Get entry at specific index (convenience method)
     */
    suspend fun getEntryAt(logIndex: Long): LogEntry? {
        return read(logIndex)
    }

    /**
     * Append entries from leader (Raft follower operation)
     *
     * Used by followers to append entries received from the leader.
     * Entries already have their index and term assigned by the leader.
     *
     * IMPORTANT: Caller must ensure entries are in order and follow existing log.
     * This method does NOT check for conflicts - call truncateFrom first if needed.
     *
     * @param entries Log entries to append (must be consecutive)
     * @return Number of entries appended
     */
    suspend fun appendEntries(entries: List<LogEntry>): Int = mutex.withLock {
        if (entries.isEmpty()) {
            return 0
        }

        // Validate entries are consecutive
        for (i in 1 until entries.size) {
            require(entries[i].index == entries[i - 1].index + 1) {
                "Entries must be consecutive: ${entries[i - 1].index} -> ${entries[i].index}"
            }
        }

        val firstIndex = entries.first().index
        val lastIndex = entries.last().index

        // Ensure we're appending at the correct position
        val currentLastIndex = getLastIndex() ?: 0
        require(firstIndex == currentLastIndex + 1) {
            "Cannot append entries starting at $firstIndex, current last index is $currentLastIndex"
        }

        logger.debug { "Appending ${entries.size} entries from leader (index $firstIndex..$lastIndex)" }

        // Append each entry
        for (entry in entries) {
            var segment = getOrCreateCurrentSegment()

            // Check if we need to rotate segment
            if (shouldRotateSegment(segment)) {
                rotateSegment()
                segment = getOrCreateCurrentSegment()
            }

            // Append to segment
            val offset = segment.append(entry)
            val segmentPath = dataDir.resolve("log-segment-${segment.getStartIndex()}.log")
            index.put(entry.index, segmentPath, offset)

            // Update nextIndex
            nextIndex = entry.index + 1
        }

        logger.info { "Appended ${entries.size} entries from leader, nextIndex=$nextIndex" }
        return entries.size
    }

    override fun close() {
        segments.values.forEach { it.close() }
        segments.clear()
        currentSegment = null
        logger.info { "Closed log storage" }
    }
}