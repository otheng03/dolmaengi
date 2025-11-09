package dolmeangi.kotlin.kvstore.handler

import dolmeangi.kotlin.common.network.RequestHandler
import dolmeangi.kotlin.common.protocol.ErrorType
import dolmeangi.kotlin.common.protocol.Response
import dolmeangi.kotlin.common.protocol.ResponseEncoder
import dolmeangi.kotlin.kvstore.protocol.Command
import dolmeangi.kotlin.kvstore.protocol.CommandParser
import dolmeangi.kotlin.kvstore.protocol.ParseException
import dolmeangi.kotlin.kvstore.protocol.TransactionId
import dolmeangi.kotlin.kvstore.transaction.CommitResult
import dolmeangi.kotlin.kvstore.transaction.TransactionException
import dolmeangi.kotlin.kvstore.transaction.TransactionManager
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * DKSP (Distributed KV Store Protocol) request handler
 *
 * This handler:
 * 1. Parses DKSP commands from request strings
 * 2. Executes commands using the TransactionManager
 * 3. Encodes responses in DKSP format
 */
class KVSPPHandler(
    private val txnManager: TransactionManager
) : RequestHandler {

    override suspend fun handle(request: String): String {
        return try {
            // Parse command
            val command = CommandParser.parse(request)
            logger.debug { "Parsed command: $command" }

            // Execute command
            val response = executeCommand(command)

            // Encode response
            ResponseEncoder.encode(response)

        } catch (e: ParseException) {
            // Parse error
            logger.debug { "Parse error: ${e.message}" }
            ResponseEncoder.encode(
                Response.Error(ErrorType.ERR, e.message ?: "Parse error")
            )

        } catch (e: TransactionException) {
            // Transaction error
            logger.debug { "Transaction error: ${e.message}" }
            val errorType = when (e) {
                is TransactionException.NotFound -> ErrorType.NOTFOUND
                is TransactionException.AlreadyAborted -> ErrorType.ABORTED
                is TransactionException.AlreadyCommitted -> ErrorType.ERR
                is TransactionException.SequencerUnavailable -> ErrorType.ERR
            }
            ResponseEncoder.encode(
                Response.Error(errorType, e.message ?: "Transaction error")
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
    private suspend fun executeCommand(command: Command): Response {
        return when (command) {
            is Command.Begin -> handleBegin()
            is Command.Commit -> handleCommit(command.txnId)
            is Command.Abort -> handleAbort(command.txnId)
            is Command.Get -> handleGet(command.txnId, command.key)
            is Command.Put -> handlePut(command.txnId, command.key, command.value)
            is Command.Delete -> handleDelete(command.txnId, command.key)
        }
    }

    private suspend fun handleBegin(): Response {
        val txnId = txnManager.begin()
        logger.debug { "Started transaction ${txnId.value}" }
        return Response.Integer(txnId.value)
    }

    private fun handleCommit(txnId: TransactionId): Response {
        return when (val result = txnManager.commit(txnId)) {
            is CommitResult.Success -> {
                logger.debug { "Committed transaction ${txnId.value}" }
                Response.OK
            }
            is CommitResult.Conflict -> {
                logger.debug { "Transaction ${txnId.value} conflict on key '${result.key}'" }
                Response.Error(
                    ErrorType.CONFLICT,
                    "Write-write conflict on key '${result.key}'"
                )
            }
        }
    }

    private fun handleAbort(txnId: TransactionId): Response {
        txnManager.abort(txnId)
        logger.debug { "Aborted transaction ${txnId.value}" }
        return Response.OK
    }

    private fun handleGet(txnId: TransactionId, key: String): Response {
        val value = txnManager.get(txnId, key)
        return if (value != null) {
            Response.StringValue(value)
        } else {
            Response.Null
        }
    }

    private fun handlePut(txnId: TransactionId, key: String, value: String): Response {
        txnManager.put(txnId, key, value)
        return Response.OK
    }

    private fun handleDelete(txnId: TransactionId, key: String): Response {
        txnManager.delete(txnId, key)
        return Response.OK
    }
}