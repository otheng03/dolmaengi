package dolmeangi.kotlin.common.client

import dolmeangi.kotlin.common.transaction.SequenceGenerator
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers

/**
 * Configuration for the Sequencer client.
 */
data class SequencerClientConfig(
    val host: String = "localhost",
    val port: Int = 10001,
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 5_000
)

/**
 * Thin TCP client that speaks the sequencer DKSP dialect.
 *
 * The client opens a persistent socket, serializes commands in flight, and parses
 * integer/error responses produced by [dolmeangi.kotlin.sequencer.handler.SequencerHandler].
 */
class SequencerClient(
    private val config: SequencerClientConfig = SequencerClientConfig(),
    private val selectorFactory: () -> SelectorManager = { SelectorManager(Dispatchers.IO) }
) : SequenceGenerator, AutoCloseable {

    private val tcpClient = TCPClient(
        config = TCPClientConfig(
            host = config.host,
            port = config.port,
            connectTimeoutMs = config.connectTimeoutMs,
            requestTimeoutMs = config.requestTimeoutMs,
            appendLineTerminator = true
        ),
        selectorFactory = selectorFactory
    )

    override suspend fun getNext(): Long = execute("GETSEQ")

    override suspend fun getCurrent(): Long = execute("CURRENT")

    private suspend fun execute(command: String): Long = try {
        tcpClient.send(command, ::parseIntegerResponse)
    } catch (e: TCPClientException) {
        throw SequencerClientException(e.message ?: "TCP client error", e)
    }

    private fun parseIntegerResponse(line: String): Long = when {
        line.startsWith(":") -> line.substring(1).toLongOrNull()
            ?: throw SequencerClientException("Invalid integer payload: $line")
        line.startsWith("-") -> throw SequencerClientException(parseError(line))
        else -> throw SequencerClientException("Unexpected response: $line")
    }

    private fun parseError(line: String): String {
        val payload = line.drop(1) // remove leading '-'
        val spaceIndex = payload.indexOf(' ')

        return if (spaceIndex == -1) {
            "Server error: ${payload.trim()}"
        } else {
            val type = payload.substring(0, spaceIndex)
            val message = payload.substring(spaceIndex + 1).trim()
            "Server error $type: $message"
        }
    }

    suspend fun disconnect() {
        tcpClient.disconnect()
    }

    override fun close() {
        tcpClient.close()
    }
}

class SequencerClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
