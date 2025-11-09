package dolmeangi.kotlin.kvstore.protocol

/**
 * Represents a transaction ID
 */
@JvmInline
value class TransactionId(val value: Long)

/**
 * Represents a DKSP command parsed from the client
 */
sealed interface Command {
    /**
     * BEGIN - Start a new transaction
     */
    data object Begin : Command

    /**
     * COMMIT - Commit a transaction
     */
    data class Commit(val txnId: TransactionId) : Command

    /**
     * ABORT - Abort a transaction
     */
    data class Abort(val txnId: TransactionId) : Command

    /**
     * GET - Read a value within a transaction
     */
    data class Get(val txnId: TransactionId, val key: String) : Command

    /**
     * PUT - Write a key-value pair within a transaction
     */
    data class Put(val txnId: TransactionId, val key: String, val value: String) : Command

    /**
     * DELETE - Delete a key within a transaction
     */
    data class Delete(val txnId: TransactionId, val key: String) : Command
}