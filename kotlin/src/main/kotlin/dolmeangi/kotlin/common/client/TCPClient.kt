package dolmeangi.kotlin.common.client

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

/**
 * TCP client configuration shared by protocol implementations.
 */
data class TCPClientConfig(
    val host: String = "localhost",
    val port: Int = 10000,
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 5_000,
    val appendLineTerminator: Boolean = true,
    val lineTerminator: String = "\r\n"
)

/**
 * Lightweight TCP client that keeps a persistent socket and serializes requests.
 *
 * The client is protocol-agnostic: it writes plain strings (optionally appending CRLF)
 * and returns each response line without the terminator.
 */
class TCPClient(
    private val config: TCPClientConfig,
    private val selectorFactory: () -> SelectorManager = { SelectorManager(Dispatchers.IO) }
) : AutoCloseable {

    private val requestMutex = Mutex()

    private var selectorManager: SelectorManager? = null
    private var socket: Socket? = null
    private var inputChannel: ByteReadChannel? = null
    private var outputChannel: ByteWriteChannel? = null

    suspend fun send(command: String): String = requestMutex.withLock {
        ensureConnected()

        val output = outputChannel ?: throw TCPClientException("TCP client is not connected")
        val input = inputChannel ?: throw TCPClientException("TCP client is not connected")

        val payload = if (config.appendLineTerminator) {
            command + config.lineTerminator
        } else {
            command
        }

        try {
            withTimeout(config.requestTimeoutMs) {
                output.writeStringUtf8(payload)
                output.flush()
            }

            val response = withTimeout(config.requestTimeoutMs) {
                input.readUTF8Line()
            } ?: throw TCPClientException("Connection closed while awaiting response")

            logger.debug { "TCPClient response <- $response" }
            return response
        } catch (e: TimeoutCancellationException) {
            closeInternal()
            throw TCPClientException("Timed out waiting for response", e)
        } catch (e: Exception) {
            closeInternal()
            throw TCPClientException("Failed to execute request", e)
        }
    }

    suspend fun <T> send(command: String, parser: (String) -> T): T =
        parser(send(command))

    private suspend fun ensureConnected() {
        if (socket != null) {
            return
        }

        closeInternal()

        val selector = selectorFactory()
        try {
            val newSocket = withTimeout(config.connectTimeoutMs) {
                aSocket(selector).tcp().connect(config.host, config.port)
            }
            selectorManager = selector
            socket = newSocket
            inputChannel = newSocket.openReadChannel()
            outputChannel = newSocket.openWriteChannel(autoFlush = true)
            logger.debug { "TCPClient connected to ${config.host}:${config.port}" }
        } catch (e: TimeoutCancellationException) {
            selector.close()
            throw TCPClientException("Timed out connecting to ${config.host}:${config.port}", e)
        } catch (e: Exception) {
            selector.close()
            throw TCPClientException("Failed to connect to ${config.host}:${config.port}", e)
        }
    }

    private fun closeInternal() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            selectorManager?.close()
        } catch (_: Exception) {
        }

        socket = null
        selectorManager = null
        inputChannel = null
        outputChannel = null
    }

    suspend fun disconnect() {
        requestMutex.withLock {
            closeInternal()
        }
    }

    override fun close() {
        runBlocking {
            requestMutex.withLock {
                closeInternal()
            }
        }
    }
}

class TCPClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
