package dolmeangi.kotlin.logserver.handler

import dolmeangi.kotlin.common.network.RequestHandler
import dolmeangi.kotlin.common.protocol.ErrorType
import dolmeangi.kotlin.common.protocol.Response
import dolmeangi.kotlin.common.protocol.ResponseEncoder
import dolmeangi.kotlin.logserver.protocol.LogCommand
import dolmeangi.kotlin.logserver.protocol.LogCommandParser
import dolmeangi.kotlin.logserver.protocol.LogParseException
import dolmeangi.kotlin.logserver.storage.LogStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Request handler for the Log Server
 *
 * Supported commands:
 * - APPEND_LOG: Append a committed transaction's writes to the log
 * - GET_LOGS: Get log entries in a range
 * - GET_LAST_INDEX: Get the last log index
 *
 * @param storage The log storage instance to use
 */
class LogServerHandler(
    private val storage: LogStorage
) : RequestHandler {

    private val json = Json { prettyPrint = false }

    override suspend fun handle(request: String): String {
        return try {
            // Parse command
            val command = LogCommandParser.parse(request)
            logger.debug { "Parsed command: $command" }

            // Execute command
            val response = executeCommand(command)

            // Encode response
            ResponseEncoder.encode(response)

        } catch (e: LogParseException) {
            // Parse error
            logger.debug { "Parse error: ${e.message}" }
            ResponseEncoder.encode(
                Response.Error(ErrorType.INVALID, e.message ?: "Parse error")
            )

        } catch (e: Exception) {
            // Unexpected error
            logger.error(e) { "Unexpected error handling request: $request" }
            ResponseEncoder.encode(
                Response.Error(ErrorType.ERR, "Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Execute a parsed command
     */
    private suspend fun executeCommand(command: LogCommand): Response {
        return when (command) {
            is LogCommand.AppendLog -> handleAppendLog(command)
            is LogCommand.GetLogs -> handleGetLogs(command)
            is LogCommand.GetLastIndex -> handleGetLastIndex()
        }
    }

    /**
     * Handle APPEND_LOG command
     */
    private suspend fun handleAppendLog(command: LogCommand.AppendLog): Response {
        val logIndex = storage.append(
            transactionId = command.transactionId,
            commitTimestamp = command.commitTimestamp,
            writes = command.writes
        )

        logger.debug {
            "Appended log: txn=${command.transactionId}, " +
            "index=$logIndex, writes=${command.writes.size}"
        }

        return Response.Integer(logIndex)
    }

    /**
     * Handle GET_LOGS command
     */
    private suspend fun handleGetLogs(command: LogCommand.GetLogs): Response {
        val entries = if (command.endIndex != null) {
            storage.readRange(command.startIndex, command.endIndex)
        } else {
            storage.readFrom(command.startIndex)
        }

        if (entries.isEmpty()) {
            return Response.Error(ErrorType.NOTFOUND, "No log entries found in range")
        }

        // Format: LOGS <count>\r\n<entry_1_json>\r\n<entry_2_json>\r\n...
        val logsJson = entries.joinToString("\n") { entry ->
            json.encodeToString(entry)
        }

        val responseText = "LOGS ${entries.size}\n$logsJson"

        logger.debug {
            "Retrieved ${entries.size} log entries " +
            "(${command.startIndex} to ${command.endIndex ?: "end"})"
        }

        return Response.StringValue(responseText)
    }

    /**
     * Handle GET_LAST_INDEX command
     */
    private suspend fun handleGetLastIndex(): Response {
        val lastIndex = storage.getLastIndex()

        return if (lastIndex != null) {
            logger.debug { "Last log index: $lastIndex" }
            Response.Integer(lastIndex)
        } else {
            logger.debug { "No log entries exist yet" }
            Response.Error(ErrorType.NOTFOUND, "No log entries exist")
        }
    }
}