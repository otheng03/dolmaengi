package dolmeangi.kotlin.common.network

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

private val logger = KotlinLogging.logger {}

/**
 * Handles a single client connection
 *
 * This class manages the lifecycle of a connection, including:
 * - Reading lines from the socket
 * - Processing requests via RequestHandler
 * - Writing responses back to the socket
 * - Handling timeouts and errors
 *
 * @param socket The client socket
 * @param config Server configuration
 * @param handler Request handler for processing commands
 */
class ConnectionHandler(
    private val socket: Socket,
    private val config: ServerConfig,
    private val handler: RequestHandler
) {
    private val remoteAddress = socket.remoteAddress
    private val lineBuffer = LineBuffer(config.maxLineSize)

    /**
     * Handle the connection lifecycle
     *
     * This suspending function will:
     * 1. Read lines from the socket
     * 2. Process each line via the handler
     * 3. Write responses back
     * 4. Handle errors and timeouts
     * 5. Close the connection on completion
     */
    suspend fun handle() {
        logger.info { "New connection from $remoteAddress" }

        try {
            withTimeout(config.idleTimeoutMs) {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = true)

                processConnection(input, output)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "Connection from $remoteAddress timed out" }
        } catch (e: LineTooLongException) {
            logger.warn { "Line too long from $remoteAddress: ${e.message}" }
            try {
                sendError(socket.openWriteChannel(autoFlush = true), "Line too long")
            } catch (ex: Exception) {
                logger.error(ex) { "Failed to send error response" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling connection from $remoteAddress" }
        } finally {
            try {
                socket.close()
                logger.info { "Connection from $remoteAddress closed" }
            } catch (e: Exception) {
                logger.error(e) { "Error closing socket" }
            }
        }
    }

    /**
     * Process the connection read/write loop
     */
    private suspend fun processConnection(input: ByteReadChannel, output: ByteWriteChannel) {
        val buffer = ByteArray(config.socketBufferSize)

        while (!input.isClosedForRead) {
            // Read bytes from socket with timeout
            val bytesRead = withTimeoutOrNull(config.readTimeoutMs) {
                input.readAvailable(buffer)
            }

            if (bytesRead == null) {
                logger.warn { "Read timeout for $remoteAddress" }
                break
            }

            if (bytesRead == -1) {
                // EOF reached
                logger.debug { "EOF reached for $remoteAddress" }
                break
            }

            if (bytesRead == 0) {
                continue
            }

            // Extract complete lines from buffer
            val lines = lineBuffer.append(buffer, bytesRead)

            // Process each line
            for (line in lines) {
                logger.debug { "Received from $remoteAddress: $line" }

                try {
                    // Process request
                    val response = handler.handle(line)

                    // Write response with timeout
                    withTimeoutOrNull(config.writeTimeoutMs) {
                        output.writeStringUtf8(response)
                        output.flush()
                    } ?: run {
                        logger.warn { "Write timeout for $remoteAddress" }
                        return
                    }

                    logger.debug { "Sent to $remoteAddress: ${response.trim()}" }
                } catch (e: Exception) {
                    logger.error(e) { "Error processing request from $remoteAddress: $line" }
                    sendError(output, "Internal server error")
                }
            }
        }
    }

    /**
     * Send an error response to the client
     */
    private suspend fun sendError(output: ByteWriteChannel, message: String) {
        try {
            output.writeStringUtf8("-ERR $message\r\n")
            output.flush()
        } catch (e: Exception) {
            logger.error(e) { "Failed to send error message" }
        }
    }
}
