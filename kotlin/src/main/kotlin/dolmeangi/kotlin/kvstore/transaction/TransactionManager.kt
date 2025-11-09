package dolmeangi.kotlin.kvstore.transaction

import dolmeangi.kotlin.kvstore.protocol.TransactionId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.iterator
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

/**
 * Result of a commit operation
 */
sealed interface CommitResult {
    data object Success : CommitResult
    data class Conflict(val key: String) : CommitResult
}

/**
 * Exception thrown when transaction operation fails
 */
sealed class TransactionException(message: String) : Exception(message) {
    class NotFound(txnId: TransactionId) : TransactionException("Transaction ${txnId.value} not found")
    class AlreadyAborted(txnId: TransactionId) : TransactionException("Transaction ${txnId.value} was aborted")
    class AlreadyCommitted(txnId: TransactionId) : TransactionException("Transaction ${txnId.value} was already committed")
}

/**
 * Transaction Manager implementing MVCC with Snapshot Isolation
 *
 * Key features:
 * - Each transaction operates on a snapshot of the database
 * - Reads are non-blocking (read committed data)
 * - Writes are buffered in transaction's write set
 * - Conflict detection at commit time (write-write conflicts)
 * - Optimistic concurrency control
 */
class TransactionManager {

    // Global transaction ID counter
    private val txnIdCounter = AtomicLong(0)

    // Active transactions
    private val transactions = ConcurrentHashMap<TransactionId, Transaction>()

    // Version store: key -> list of versions
    private val versionStore = ConcurrentHashMap<String, MutableList<Version>>()

    // Lock for commit operations (serializes commits)
    private val commitLock = ReentrantReadWriteLock()

    // Last committed transaction ID (for snapshot isolation)
    private val lastCommittedTxnId = AtomicLong(0)

    /**
     * Begin a new transaction
     */
    fun begin(): TransactionId {
        val txnId = TransactionId(txnIdCounter.incrementAndGet())
        val snapshot = lastCommittedTxnId.get()

        val transaction = Transaction(
            id = txnId,
            snapshotVersion = snapshot,
            state = TransactionState.ACTIVE
        )

        transactions[txnId] = transaction
        logger.debug { "Transaction ${txnId.value} started (snapshot: $snapshot)" }

        return txnId
    }

    /**
     * Get a value within a transaction
     */
    fun get(txnId: TransactionId, key: String): String? {
        val txn = getActiveTransaction(txnId)

        // First check transaction's write set
        txn.writeSet[key]?.let { return it }

        // Then check version store for latest committed version visible to this snapshot
        val versions = versionStore[key] ?: return null

        synchronized(versions) {
            return versions
                .filter { it.txnId <= txn.snapshotVersion }
                .maxByOrNull { it.txnId }
                ?.value
        }
    }

    /**
     * Put a key-value pair within a transaction
     */
    fun put(txnId: TransactionId, key: String, value: String) {
        val txn = getActiveTransaction(txnId)

        // Add to write set
        txn.writeSet[key] = value
        logger.debug { "Transaction ${txnId.value}: PUT $key = $value" }
    }

    /**
     * Delete a key within a transaction (implemented as putting a null marker)
     */
    fun delete(txnId: TransactionId, key: String) {
        val txn = getActiveTransaction(txnId)

        // Mark as deleted in write set
        txn.writeSet[key] = null
        logger.debug { "Transaction ${txnId.value}: DELETE $key" }
    }

    /**
     * Commit a transaction
     *
     * Uses optimistic concurrency control:
     * 1. Validate no write-write conflicts
     * 2. Apply writes to version store
     * 3. Mark transaction as committed
     */
    fun commit(txnId: TransactionId): CommitResult {
        val txn = transactions[txnId]
            ?: throw TransactionException.NotFound(txnId)

        if (txn.state == TransactionState.ABORTED) {
            throw TransactionException.AlreadyAborted(txnId)
        }

        if (txn.state == TransactionState.COMMITTED) {
            throw TransactionException.AlreadyCommitted(txnId)
        }

        // If no writes, just mark as committed
        if (txn.writeSet.isEmpty()) {
            txn.state = TransactionState.COMMITTED
            logger.debug { "Transaction ${txnId.value} committed (no writes)" }
            return CommitResult.Success
        }

        // Serialize commits to detect conflicts
        return commitLock.write {
            // Validate write-write conflicts
            for (key in txn.writeSet.keys) {
                val versions = versionStore[key]
                if (versions != null) {
                    synchronized(versions) {
                        // Check if any committed transaction after our snapshot wrote to this key
                        val hasConflict = versions.any { version ->
                            version.txnId > txn.snapshotVersion
                        }

                        if (hasConflict) {
                            logger.debug { "Transaction ${txnId.value} aborted: conflict on key '$key'" }
                            txn.state = TransactionState.ABORTED
                            return CommitResult.Conflict(key)
                        }
                    }
                }
            }

            // No conflicts - apply writes
            val commitTxnId = txnId.value

            for ((key, value) in txn.writeSet) {
                val versions = versionStore.computeIfAbsent(key) { mutableListOf() }

                synchronized(versions) {
                    if (value != null) {
                        // PUT operation
                        versions.add(Version(commitTxnId, value))
                    } else {
                        // DELETE operation - add tombstone
                        versions.add(Version(commitTxnId, null))
                    }
                }
            }

            // Update last committed transaction ID
            lastCommittedTxnId.set(commitTxnId)
            txn.state = TransactionState.COMMITTED

            logger.debug { "Transaction ${txnId.value} committed successfully" }
            CommitResult.Success
        }
    }

    /**
     * Abort a transaction
     */
    fun abort(txnId: TransactionId) {
        val txn = transactions[txnId]
            ?: throw TransactionException.NotFound(txnId)

        txn.state = TransactionState.ABORTED
        logger.debug { "Transaction ${txnId.value} aborted" }
    }

    /**
     * Get an active transaction or throw exception
     */
    private fun getActiveTransaction(txnId: TransactionId): Transaction {
        val txn = transactions[txnId]
            ?: throw TransactionException.NotFound(txnId)

        if (txn.state == TransactionState.ABORTED) {
            throw TransactionException.AlreadyAborted(txnId)
        }

        if (txn.state == TransactionState.COMMITTED) {
            throw TransactionException.AlreadyCommitted(txnId)
        }

        return txn
    }

    /**
     * Get transaction count (for debugging/testing)
     */
    fun getTransactionCount(): Int = transactions.size

    /**
     * Clean up old transactions (for garbage collection)
     * TODO: Implement proper garbage collection
     */
    fun cleanup() {
        val completed = transactions.filter { (_, txn) ->
            txn.state == TransactionState.COMMITTED || txn.state == TransactionState.ABORTED
        }

        completed.keys.forEach { transactions.remove(it) }
        logger.debug { "Cleaned up ${completed.size} completed transactions" }
    }
}

/**
 * Transaction state
 */
enum class TransactionState {
    ACTIVE,
    COMMITTED,
    ABORTED
}

/**
 * Transaction metadata
 */
data class Transaction(
    val id: TransactionId,
    val snapshotVersion: Long,
    var state: TransactionState,
    val writeSet: MutableMap<String, String?> = mutableMapOf()
)

/**
 * Version of a key-value pair
 */
data class Version(
    val txnId: Long,
    val value: String?  // null means deleted (tombstone)
)