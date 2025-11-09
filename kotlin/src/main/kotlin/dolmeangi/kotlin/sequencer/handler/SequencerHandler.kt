package dolmeangi.kotlin.sequencer.handler

import dolmeangi.kotlin.common.network.RequestHandler
import dolmeangi.kotlin.common.protocol.ErrorType
import dolmeangi.kotlin.common.protocol.Response
import dolmeangi.kotlin.common.protocol.ResponseEncoder
import dolmeangi.kotlin.sequencer.Sequencer
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Request handler for the Sequencer service
 *
 * Supported commands:
 * - GETSEQ: Get the next sequence number
 * - CURRENT: Get the current sequence number (without incrementing)
 *
 * @param sequencer The sequencer instance to use
 */
class SequencerHandler(
    private val sequencer: Sequencer
) : RequestHandler {

    override suspend fun handle(request: String): String {
        return try {
            val command = request.trim().uppercase()

            val response = when (command) {
                "GETSEQ" -> handleGetSeq()
                "CURRENT" -> handleCurrent()
                else -> Response.Error(ErrorType.ERR, "Unknown command '$command'. Supported: GETSEQ, CURRENT")
            }

            ResponseEncoder.encode(response)

        } catch (e: Exception) {
            logger.error(e) { "Unexpected error handling request: $request" }
            ResponseEncoder.encode(
                Response.Error(ErrorType.ERR, "Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Handle GETSEQ command - return next sequence number
     */
    private fun handleGetSeq(): Response {
        val next = sequencer.getNext()
        logger.debug { "GETSEQ -> $next" }
        return Response.Integer(next)
    }

    /**
     * Handle CURRENT command - return current sequence number without incrementing
     */
    private fun handleCurrent(): Response {
        val current = sequencer.getCurrent()
        logger.debug { "CURRENT -> $current" }
        return Response.Integer(current)
    }
}