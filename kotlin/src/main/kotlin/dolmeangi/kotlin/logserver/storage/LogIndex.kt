package dolmeangi.kotlin.logserver.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListMap

private val logger = KotlinLogging.logger {}

/**
 * In-memory index mapping log index to (segment, file offset)
 *
 * For Phase 1 (single-node), we'll keep this simple with an in-memory structure.
 * In Phase 2+, this can be persisted to disk or memory-mapped.
 */
class LogIndex {

    private val index = ConcurrentSkipListMap<Long, IndexEntry>()

    /**
     * Add an entry to the index
     */
    fun put(logIndex: Long, segmentPath: Path, fileOffset: Long) {
        index[logIndex] = IndexEntry(segmentPath, fileOffset)
        logger.trace { "Indexed log entry: index=$logIndex, segment=$segmentPath, offset=$fileOffset" }
    }

    /**
     * Get the index entry for a log index
     */
    fun get(logIndex: Long): IndexEntry? {
        return index[logIndex]
    }

    /**
     * Get all log indices in the given range [startIndex, endIndex] inclusive
     */
    fun getRange(startIndex: Long, endIndex: Long): Map<Long, IndexEntry> {
        return index.subMap(startIndex, true, endIndex, true)
    }

    /**
     * Get all log indices from startIndex onwards
     */
    fun getFrom(startIndex: Long): Map<Long, IndexEntry> {
        return index.tailMap(startIndex, true)
    }

    /**
     * Get the last (highest) log index
     */
    fun getLastIndex(): Long? {
        return if (index.isEmpty()) null else index.lastKey()
    }

    /**
     * Get the first (lowest) log index
     */
    fun getFirstIndex(): Long? {
        return if (index.isEmpty()) null else index.firstKey()
    }

    /**
     * Check if the index is empty
     */
    fun isEmpty(): Boolean {
        return index.isEmpty()
    }

    /**
     * Get the total number of indexed entries
     */
    fun size(): Int {
        return index.size
    }

    /**
     * Clear the entire index
     */
    fun clear() {
        index.clear()
        logger.info { "Cleared log index" }
    }

    /**
     * Remove entries with log index less than the given index
     */
    fun removeBefore(logIndex: Long): Int {
        val toRemove = index.headMap(logIndex, false).keys.toList()
        toRemove.forEach { index.remove(it) }
        logger.info { "Removed ${toRemove.size} entries before index $logIndex" }
        return toRemove.size
    }
}

/**
 * Index entry pointing to a log entry's location
 */
data class IndexEntry(
    val segmentPath: Path,
    val fileOffset: Long
)