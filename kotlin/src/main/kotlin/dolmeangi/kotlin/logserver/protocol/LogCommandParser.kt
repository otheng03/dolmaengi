package dolmeangi.kotlin.logserver.protocol

import dolmeangi.kotlin.logserver.model.WriteOperation
import dolmeangi.kotlin.logserver.model.WriteType
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Exception thrown when log command parsing fails
 */
class LogParseException(message: String) : Exception(message)

/**
 * Parser for Log Server DKSP commands
 *
 * Parses command strings into LogCommand objects.
 */
object LogCommandParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse a command line into a LogCommand object
     *
     * @param line The command line (without CRLF)
     * @return Parsed LogCommand
     * @throws LogParseException if parsing fails
     */
    fun parse(line: String): LogCommand {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            throw LogParseException("Empty command")
        }

        // Split into tokens
        val tokens = trimmed.split(" ", limit = 4)
        val command = tokens[0].uppercase()

        return when (command) {
            "APPEND_LOG" -> parseAppendLog(tokens)
            "GET_LOGS" -> parseGetLogs(tokens)
            "GET_LAST_INDEX" -> parseGetLastIndex(tokens)
            else -> throw LogParseException("Unknown command '$command'")
        }
    }

    /**
     * Parse APPEND_LOG command
     *
     * Format: APPEND_LOG <txn_id> <commit_ts> <writes_json>
     *
     * Example:
     * APPEND_LOG 1001 1234567890 [{"type":"PUT","key":"k1","value":"v1","version":1}]
     */
    private fun parseAppendLog(tokens: List<String>): LogCommand.AppendLog {
        if (tokens.size < 4) {
            throw LogParseException("APPEND_LOG: Expected 4 arguments, got ${tokens.size}")
        }

        val txnId = tokens[1].toLongOrNull()
            ?: throw LogParseException("APPEND_LOG: Invalid transaction ID '${tokens[1]}'")

        val commitTs = tokens[2].toLongOrNull()
            ?: throw LogParseException("APPEND_LOG: Invalid commit timestamp '${tokens[2]}'")

        // Parse JSON array of writes
        val writesJson = tokens[3]
        val writes = try {
            json.decodeFromString<List<WriteOperation>>(writesJson)
        } catch (e: Exception) {
            throw LogParseException("APPEND_LOG: Invalid writes JSON: ${e.message}")
        }

        if (writes.isEmpty()) {
            throw LogParseException("APPEND_LOG: Writes list cannot be empty")
        }

        return LogCommand.AppendLog(txnId, commitTs, writes)
    }

    /**
     * Parse GET_LOGS command
     *
     * Format: GET_LOGS <start_index> [end_index]
     *
     * Examples:
     * GET_LOGS 1
     * GET_LOGS 1 10
     */
    private fun parseGetLogs(tokens: List<String>): LogCommand.GetLogs {
        if (tokens.size < 2) {
            throw LogParseException("GET_LOGS: Expected at least 2 arguments, got ${tokens.size}")
        }

        if (tokens.size > 3) {
            throw LogParseException("GET_LOGS: Expected at most 3 arguments, got ${tokens.size}")
        }

        val startIndex = tokens[1].toLongOrNull()
            ?: throw LogParseException("GET_LOGS: Invalid start index '${tokens[1]}'")

        val endIndex = if (tokens.size == 3) {
            tokens[2].toLongOrNull()
                ?: throw LogParseException("GET_LOGS: Invalid end index '${tokens[2]}'")
        } else {
            null
        }

        if (endIndex != null && endIndex < startIndex) {
            throw LogParseException("GET_LOGS: End index must be >= start index")
        }

        return LogCommand.GetLogs(startIndex, endIndex)
    }

    /**
     * Parse GET_LAST_INDEX command
     *
     * Format: GET_LAST_INDEX
     */
    private fun parseGetLastIndex(tokens: List<String>): LogCommand.GetLastIndex {
        if (tokens.size != 1) {
            throw LogParseException("GET_LAST_INDEX: Expected no arguments, got ${tokens.size - 1}")
        }

        return LogCommand.GetLastIndex
    }

    /**
     * Helper: Encode writes to JSON string for APPEND_LOG command
     */
    fun encodeWrites(writes: List<WriteOperation>): String {
        return json.encodeToString(writes)
    }
}