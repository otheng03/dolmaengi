```kotlin
interface TransactionManager {
    fun beginTransaction(): TransactionId
    suspend fun get(txnId: TransactionId, key: String): String?
    suspend fun put(txnId: TransactionId, key: String, value: String)
    suspend fun delete(txnId: TransactionId, key: String)
    suspend fun commit(txnId: TransactionId): CommitResult
    suspend fun abort(txnId: TransactionId)
}

sealed class CommitResult {
    object Success : CommitResult()
    data class Aborted(val reason: String) : CommitResult()
}
```
