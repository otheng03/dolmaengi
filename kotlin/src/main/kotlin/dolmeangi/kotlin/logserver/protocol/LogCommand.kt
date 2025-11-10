package dolmeangi.kotlin.logserver.protocol

import dolmeangi.kotlin.logserver.model.WriteOperation

/**
 * Represents a log server command parsed from the client
 */
sealed interface LogCommand {
    /**
     * APPEND_LOG - Append a committed transaction's writes to the log
     *
     * Format:
     * APPEND_LOG <txn_id> <commit_ts> <write_count>\r\n
     * PUT <key> <value> <version>\r\n
     * DELETE <key> <version>\r\n
     * ...
     */
    data class AppendLog(
        val transactionId: Long,
        val commitTimestamp: Long,
        val writes: List<WriteOperation>
    ) : LogCommand

    /**
     * GET_LOGS - Get log entries in a range
     *
     * Format:
     * GET_LOGS <start_index> [end_index]
     *
     * If end_index is omitted, returns from start_index to end of log
     */
    data class GetLogs(
        val startIndex: Long,
        val endIndex: Long?
    ) : LogCommand

    /**
     * GET_LAST_INDEX - Get the last (highest) log index
     *
     * Format:
     * GET_LAST_INDEX
     */
    data object GetLastIndex : LogCommand
}