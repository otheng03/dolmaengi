package dolmeangi.kotlin.logserver.raft.persistence

import dolmeangi.kotlin.logserver.raft.model.RaftMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Persistent storage for Raft metadata
 *
 * Stores currentTerm and votedFor to disk with fsync for durability.
 * This ensures Raft safety properties across crashes.
 *
 * File format: JSON for human readability and debugging
 * Example: {"currentTerm": 5, "votedFor": {"id": 2}}
 *
 * @property metadataFile Path to the metadata file (e.g., raft-metadata.json)
 */
class RaftMetadataStore(
    private val metadataFile: Path
) : AutoCloseable {

    private val json = Json { prettyPrint = true }
    private val mutex = Mutex()

    @Volatile
    private var cachedMetadata: RaftMetadata = RaftMetadata()

    init {
        // Create parent directory if needed
        metadataFile.parent?.let { parent ->
            if (!parent.exists()) {
                parent.createDirectories()
                logger.info { "Created metadata directory: $parent" }
            }
        }

        // Load existing metadata if file exists
        if (metadataFile.exists()) {
            loadFromDisk()
        } else {
            // Initialize with default metadata
            logger.info { "No existing metadata found, initializing with term=0" }
            syncToDisk(cachedMetadata)
        }
    }

    /**
     * Load metadata from disk
     */
    private fun loadFromDisk() {
        try {
            val jsonText = metadataFile.readText()
            cachedMetadata = json.decodeFromString<RaftMetadata>(jsonText)
            logger.info { "Loaded Raft metadata from disk: $cachedMetadata" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load metadata from $metadataFile" }
            throw IllegalStateException("Failed to load Raft metadata", e)
        }
    }

    /**
     * Get current metadata
     */
    fun get(): RaftMetadata {
        return cachedMetadata
    }

    /**
     * Update metadata and persist to disk
     *
     * IMPORTANT: This method performs fsync to ensure durability.
     * Must be called before responding to RPCs that depend on this state.
     *
     * @param metadata New metadata to persist
     */
    suspend fun update(metadata: RaftMetadata) = mutex.withLock {
        // Validate that term never decreases
        require(metadata.currentTerm >= cachedMetadata.currentTerm) {
            "Cannot decrease term from ${cachedMetadata.currentTerm} to ${metadata.currentTerm}"
        }

        // Write to disk first (durability before visibility)
        syncToDisk(metadata)

        // Update in-memory cache
        cachedMetadata = metadata

        logger.debug { "Updated Raft metadata: $metadata" }
    }

    /**
     * Write metadata to disk with fsync
     *
     * Uses atomic write pattern:
     * 1. Write to temporary file
     * 2. Fsync temporary file
     * 3. Rename to actual file (atomic operation)
     */
    private fun syncToDisk(metadata: RaftMetadata) {
        val tempFile = metadataFile.resolveSibling("${metadataFile.fileName}.tmp")

        try {
            // Serialize to JSON
            val jsonText = json.encodeToString(metadata)

            // Write to temp file
            tempFile.writeText(
                jsonText,
                options = arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC // Ensures fsync
                )
            )

            // Atomic rename
            tempFile.moveTo(metadataFile, overwrite = true)

            logger.trace { "Synced metadata to disk: $metadata" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to sync metadata to disk" }
            // Clean up temp file if it exists
            if (tempFile.exists()) {
                tempFile.deleteIfExists()
            }
            throw e
        }
    }

    /**
     * Get current term
     */
    fun getCurrentTerm(): Long = cachedMetadata.currentTerm

    /**
     * Update term and clear votedFor
     *
     * @param newTerm New term (must be >= current term)
     */
    suspend fun updateTerm(newTerm: Long) {
        val newMetadata = cachedMetadata.withTerm(newTerm)
        update(newMetadata)
    }

    /**
     * Record a vote for a candidate
     *
     * @param candidateId NodeId of the candidate
     */
    suspend fun recordVote(candidateId: dolmeangi.kotlin.logserver.raft.model.NodeId) {
        val newMetadata = cachedMetadata.withVote(candidateId)
        update(newMetadata)
    }

    /**
     * Update both term and vote atomically
     */
    suspend fun updateTermAndVote(newTerm: Long, candidateId: dolmeangi.kotlin.logserver.raft.model.NodeId) {
        val newMetadata = RaftMetadata(currentTerm = newTerm, votedFor = candidateId)
        update(newMetadata)
    }

    override fun close() {
        logger.info { "Closed Raft metadata store" }
    }
}