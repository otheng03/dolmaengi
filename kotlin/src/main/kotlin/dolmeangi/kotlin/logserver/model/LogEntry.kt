package dolmeangi.kotlin.logserver.model

import kotlinx.serialization.Serializable

/**
 * Represents a committed transaction's write operations in the log
 *
 * Each log entry contains all writes from a single committed transaction.
 * Entries are ordered by Raft log index for total ordering.
 */
@Serializable
data class LogEntry(
    val index: Long,              // Raft log index (for now, simple monotonic counter)
    val term: Long,               // Raft term (for now, always 1 in single-node)
    val transactionId: Long,      // Transaction ID from Sequencer
    val commitTimestamp: Long,    // When the transaction was committed
    val writes: List<WriteOperation>
)

/**
 * Represents a single write operation (PUT or DELETE)
 */
@Serializable
data class WriteOperation(
    val type: WriteType,
    val key: String,
    val value: String?,          // null for DELETE
    val version: Long            // MVCC version from KVStore
)

/**
 * Type of write operation
 */
@Serializable
enum class WriteType {
    PUT,
    DELETE
}
