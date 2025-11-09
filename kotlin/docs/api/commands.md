# API Commands Reference

This document describes the command API for the distributed transactional key-value store. Commands are organized by category and documented with syntax, parameters, return values, and examples.

---

## Transaction Lifecycle Commands

All data operations must occur within a transaction context. Transactions provide ACID guarantees and isolation between concurrent operations.

### BEGIN

Starts a new transaction and returns a transaction ID.

**Syntax**:
```
BEGIN
```

**Parameters**: None

**Returns**:
```kotlin
TransactionId  // Unique identifier for this transaction
```

**Example**:
```kotlin
val txnId = transactionManager.beginTransaction()
// Returns: TransactionId(value = "txn_1234567890")
```

**Notes**:
- Each transaction gets a unique, monotonically increasing ID
- Transaction IDs are used for MVCC version tracking
- Transactions are initially in "active" state

---

### COMMIT

Attempts to commit all changes made within a transaction. The commit may fail if conflicts are detected.

**Syntax**:
```
COMMIT <transaction_id>
```

**Parameters**:
- `transaction_id`: TransactionId - The transaction to commit

**Returns**:
```kotlin
sealed class CommitResult {
    object Success : CommitResult()
    data class Aborted(val reason: String) : CommitResult()
}
```

**Examples**:

Success case:
```kotlin
val result = transactionManager.commit(txnId)
// Returns: CommitResult.Success
```

Conflict case:
```kotlin
val result = transactionManager.commit(txnId)
// Returns: CommitResult.Aborted(reason = "Write-write conflict on key 'user:123'")
```

**Error Cases**:
- `Aborted("Write-write conflict on key '<key>'")` - Another transaction modified the same key
- `Aborted("Transaction not found")` - Invalid or already completed transaction ID
- `Aborted("Read-write conflict detected")` - Snapshot isolation violation

**Notes**:
- Uses optimistic concurrency control (conflict detection at commit time)
- All writes are atomic - either all succeed or all fail
- Successfully committed changes are immediately visible to new transactions
- Failed commits automatically abort the transaction

---

### ABORT

Explicitly aborts a transaction and discards all changes.

**Syntax**:
```
ABORT <transaction_id>
```

**Parameters**:
- `transaction_id`: TransactionId - The transaction to abort

**Returns**: `Unit` (void)

**Example**:
```kotlin
transactionManager.abort(txnId)
// Transaction is terminated, all changes discarded
```

**Notes**:
- Can be called at any time during a transaction
- All reads and writes in the transaction are discarded
- Transaction ID becomes invalid after abort
- Idempotent - safe to call multiple times

---

## Data Operations

All data operations are performed within the context of an active transaction.

### GET

Retrieves the value associated with a key, as visible in the transaction's snapshot.

**Syntax**:
```
GET <transaction_id> <key>
```

**Parameters**:
- `transaction_id`: TransactionId - The transaction context
- `key`: String - The key to retrieve

**Returns**:
- `String?` - The value if the key exists, `null` if not found

**Examples**:

Key exists:
```kotlin
val value = transactionManager.get(txnId, "user:alice")
// Returns: "{'name': 'Alice', 'age': 30}"
```

Key does not exist:
```kotlin
val value = transactionManager.get(txnId, "user:bob")
// Returns: null
```

Read your own writes:
```kotlin
val txnId = transactionManager.beginTransaction()
transactionManager.put(txnId, "counter", "1")
val value = transactionManager.get(txnId, "counter")
// Returns: "1"
```

**Isolation Behavior**:
- Reads see a consistent snapshot from transaction begin time
- Reads within a transaction always see writes from the same transaction
- Reads do not block writes in other transactions
- Deleted keys within the transaction return `null`

**Error Cases**:
- Throws exception if transaction ID is invalid or aborted

---

### PUT

Writes or updates a key-value pair within a transaction.

**Syntax**:
```
PUT <transaction_id> <key> <value>
```

**Parameters**:
- `transaction_id`: TransactionId - The transaction context
- `key`: String - The key to write (non-empty)
- `value`: String - The value to store

**Returns**: `Unit` (void)

**Examples**:

Insert new key:
```kotlin
transactionManager.put(txnId, "user:alice", "{'name': 'Alice', 'age': 30}")
```

Update existing key:
```kotlin
transactionManager.put(txnId, "counter", "42")
transactionManager.put(txnId, "counter", "43")  // Overwrites previous value
```

Multiple writes:
```kotlin
val txnId = transactionManager.beginTransaction()
transactionManager.put(txnId, "account:1:balance", "1000")
transactionManager.put(txnId, "account:2:balance", "2000")
transactionManager.commit(txnId)
// Both writes commit atomically
```

**Notes**:
- Writes are buffered locally until commit
- Overwrites within the same transaction replace previous values
- Empty keys are not allowed
- Values can be arbitrary strings (JSON, serialized objects, etc.)
- Writes do not block reads in other transactions

**Error Cases**:
- Throws exception if transaction ID is invalid or aborted
- Throws exception if key is empty or null

---

### DELETE

Removes a key-value pair within a transaction.

**Syntax**:
```
DELETE <transaction_id> <key>
```

**Parameters**:
- `transaction_id`: TransactionId - The transaction context
- `key`: String - The key to delete

**Returns**: `Unit` (void)

**Examples**:

Delete existing key:
```kotlin
transactionManager.delete(txnId, "user:alice")
// Key marked for deletion
```

Delete and re-insert:
```kotlin
val txnId = transactionManager.beginTransaction()
transactionManager.delete(txnId, "counter")
transactionManager.put(txnId, "counter", "0")  // Re-insert with new value
transactionManager.commit(txnId)
```

Delete non-existent key:
```kotlin
transactionManager.delete(txnId, "nonexistent")
// No error - idempotent operation
```

**Notes**:
- Deletes are buffered locally until commit
- Deleting a non-existent key is a no-op (no error)
- After delete, GET on the same key returns `null` within the transaction
- Deletes participate in conflict detection (write-write conflicts)

**Error Cases**:
- Throws exception if transaction ID is invalid or aborted

---

## Common Usage Patterns

### Basic Read-Write Transaction

```kotlin
// Start transaction
val txnId = transactionManager.beginTransaction()

try {
    // Read current value
    val currentValue = transactionManager.get(txnId, "counter")
    val newValue = (currentValue?.toInt() ?: 0) + 1

    // Write new value
    transactionManager.put(txnId, "counter", newValue.toString())

    // Commit
    when (val result = transactionManager.commit(txnId)) {
        is CommitResult.Success -> println("Counter incremented to $newValue")
        is CommitResult.Aborted -> println("Transaction failed: ${result.reason}")
    }
} catch (e: Exception) {
    // Abort on error
    transactionManager.abort(txnId)
    throw e
}
```

### Read-Only Transaction

```kotlin
val txnId = transactionManager.beginTransaction()

try {
    val user = transactionManager.get(txnId, "user:alice")
    val settings = transactionManager.get(txnId, "settings:alice")

    // Process read data
    println("User: $user")
    println("Settings: $settings")

    // Commit (or abort - no side effects for read-only)
    transactionManager.commit(txnId)
} catch (e: Exception) {
    transactionManager.abort(txnId)
}
```

### Atomic Multi-Key Update

```kotlin
val txnId = transactionManager.beginTransaction()

try {
    // Transfer money between accounts
    val balance1 = transactionManager.get(txnId, "account:1:balance")?.toInt() ?: 0
    val balance2 = transactionManager.get(txnId, "account:2:balance")?.toInt() ?: 0

    val transferAmount = 100

    if (balance1 >= transferAmount) {
        transactionManager.put(txnId, "account:1:balance", (balance1 - transferAmount).toString())
        transactionManager.put(txnId, "account:2:balance", (balance2 + transferAmount).toString())

        when (val result = transactionManager.commit(txnId)) {
            is CommitResult.Success -> println("Transfer successful")
            is CommitResult.Aborted -> println("Transfer failed: ${result.reason}")
        }
    } else {
        transactionManager.abort(txnId)
        println("Insufficient funds")
    }
} catch (e: Exception) {
    transactionManager.abort(txnId)
    throw e
}
```

### Retry on Conflict

```kotlin
fun incrementCounterWithRetry(key: String, maxRetries: Int = 3): Boolean {
    repeat(maxRetries) { attempt ->
        val txnId = transactionManager.beginTransaction()

        try {
            val current = transactionManager.get(txnId, key)?.toInt() ?: 0
            transactionManager.put(txnId, key, (current + 1).toString())

            when (val result = transactionManager.commit(txnId)) {
                is CommitResult.Success -> return true
                is CommitResult.Aborted -> {
                    println("Attempt ${attempt + 1} failed: ${result.reason}")
                    // Retry on next iteration
                }
            }
        } catch (e: Exception) {
            transactionManager.abort(txnId)
            throw e
        }
    }

    return false  // All retries exhausted
}
```

---

## Transaction Guarantees (Phase 1)

### ACID Properties

**Atomicity**:
- All operations in a transaction commit or abort together
- Guaranteed by Write-Ahead Log (WAL)
- System recovers to consistent state after crashes

**Consistency**:
- Application-level invariants maintained across transactions
- No partial updates visible to other transactions

**Isolation**:
- Snapshot Isolation level implemented via MVCC
- Transactions see a consistent snapshot from begin time
- Write-write conflicts detected and prevented at commit time

**Durability**:
- Committed transactions survive crashes
- Guaranteed by fsync on WAL before commit acknowledgment

### Isolation Level: Snapshot Isolation

**Guarantees**:
- Read your own writes
- Repeatable reads within a transaction
- No dirty reads (uncommitted data)
- No dirty writes (overwriting uncommitted data)
- Protection against write-write conflicts

**Does NOT prevent**:
- Write skew anomalies (requires serializable isolation)
- Phantom reads in some cases

**Trade-offs**:
- Better performance than full serializability
- Sufficient for most use cases
- Applications may need to handle write skew at app level

---

## Future Commands (Planned)

The following commands will be added in later phases:

### Phase 3: Distribution
- `CLUSTER STATUS` - View cluster health and topology
- `ADD NODE <address>` - Add a new node to the cluster
- `REMOVE NODE <id>` - Remove a node from the cluster
- `LIST SHARDS` - View shard distribution
- `REBALANCE` - Trigger shard rebalancing

### Phase 4: Operations
- `BACKUP <path>` - Create a backup snapshot
- `RESTORE <path>` - Restore from backup
- `COMPACT` - Trigger manual compaction
- `METRICS` - View performance metrics
- `CHECKPOINT` - Force a WAL checkpoint

---

## Error Handling

### Exception Types

```kotlin
sealed class KVStoreException : Exception() {
    data class TransactionNotFound(val txnId: TransactionId) : KVStoreException()
    data class TransactionAborted(val txnId: TransactionId, val reason: String) : KVStoreException()
    data class InvalidKey(val key: String, val reason: String) : KVStoreException()
    data class StorageError(val message: String, val cause: Throwable?) : KVStoreException()
    data class RecoveryError(val message: String) : KVStoreException()
}
```

### Best Practices

1. **Always handle commit failures**: Check `CommitResult` and implement retry logic where appropriate
2. **Use try-finally for cleanup**: Ensure transactions are aborted on exceptions
3. **Validate input**: Check for null/empty keys before operations
4. **Implement timeouts**: Long-running transactions increase conflict probability
5. **Keep transactions short**: Reduces lock contention and improves throughput

---

## Performance Considerations

### Current Phase (In-Memory)

**Strengths**:
- O(1) read/write operations (hash table)
- No disk I/O during transaction execution
- High throughput for concurrent reads

**Limitations**:
- Dataset size limited by available memory
- All data lost on crash (until WAL recovery)
- Write-heavy workloads may see commit conflicts

### Optimization Tips

1. **Batch operations**: Group multiple reads/writes in single transaction
2. **Read-only optimization**: Read-only transactions can skip commit conflict checks (future)
3. **Key design**: Use consistent key naming schemes for better access patterns
4. **Retry strategy**: Implement exponential backoff for conflict retries
5. **Monitor conflicts**: Track conflict rates to identify hotspot keys

---

## Implementation Notes

### Current Implementation Status

**Implemented** (Milestone 1):
- Basic TransactionManager interface
- Transaction lifecycle (begin, commit, abort)
- CommitResult types

**In Progress**:
- MVCC implementation
- In-memory storage backend
- Conflict detection

**Planned**:
- WAL for durability
- Recovery mechanism
- Performance benchmarks

### Technical Details

**Transaction ID Format**:
```kotlin
data class TransactionId(val value: Long)  // Monotonically increasing
```

**Version Format**:
```kotlin
data class Version(
    val txnId: TransactionId,
    val timestamp: Long,
    val value: String?  // null for deletions
)
```

**Concurrency Model**:
- Kotlin coroutines for async operations
- Lock-free reads using MVCC
- Optimistic locking for writes

---

## See Also

- [Transaction Manager API](transaction.md) - Detailed interface specification
- [Architecture Overview](../architecture.md) - System architecture and design
- [CLAUDE.md](../../CLAUDE.md) - Project roadmap and milestones