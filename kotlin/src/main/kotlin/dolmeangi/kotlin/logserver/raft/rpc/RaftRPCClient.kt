package dolmeangi.kotlin.logserver.raft.rpc

import dolmeangi.kotlin.logserver.raft.model.PeerInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Client for sending Raft RPCs to peer nodes
 *
 * Uses TCP sockets to communicate with peers over their Raft port.
 * Each RPC is a request-response exchange with a timeout.
 */
class RaftRPCClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val selectorManager = SelectorManager(Dispatchers.IO)

    /**
     * Send RequestVote RPC to a peer
     *
     * @param peer Peer information (host, raft port)
     * @param request RequestVote request
     * @param timeoutSeconds Timeout in seconds (default: 2)
     * @return RequestVote response, or null if failed/timeout
     */
    suspend fun sendRequestVote(
        peer: PeerInfo,
        request: RaftRPC.RequestVoteRequest,
        timeoutSeconds: Long = 2
    ): RaftRPC.RequestVoteResponse? {
        return try {
            withTimeout(timeoutSeconds.seconds) {
                val socket = aSocket(selectorManager).tcp().connect(peer.host, peer.raftPort)

                try {
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true)

                    // Serialize and send request
                    val requestJson = json.encodeToString<RaftRPC>(request)
                    output.writeStringUtf8("$requestJson\n")

                    // Read response
                    val responseLine = input.readUTF8Line()
                    if (responseLine == null) {
                        logger.warn { "No response from ${peer.nodeId} for RequestVote" }
                        return@withTimeout null
                    }

                    // Deserialize response
                    val response = json.decodeFromString<RaftRPC>(responseLine)
                    if (response !is RaftRPC.RequestVoteResponse) {
                        logger.warn { "Unexpected response type from ${peer.nodeId}: ${response::class.simpleName}" }
                        return@withTimeout null
                    }

                    logger.debug { "Received RequestVote response from ${peer.nodeId}: $response" }
                    response

                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to send RequestVote to ${peer.nodeId}" }
            null
        }
    }

    /**
     * Send AppendEntries RPC to a peer
     *
     * @param peer Peer information
     * @param request AppendEntries request
     * @param timeoutSeconds Timeout in seconds (default: 2)
     * @return AppendEntries response, or null if failed/timeout
     */
    suspend fun sendAppendEntries(
        peer: PeerInfo,
        request: RaftRPC.AppendEntriesRequest,
        timeoutSeconds: Long = 2
    ): RaftRPC.AppendEntriesResponse? {
        return try {
            withTimeout(timeoutSeconds.seconds) {
                val socket = aSocket(selectorManager).tcp().connect(peer.host, peer.raftPort)

                try {
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true)

                    // Serialize and send request
                    val requestJson = json.encodeToString<RaftRPC>(request)
                    output.writeStringUtf8("$requestJson\n")

                    // Read response
                    val responseLine = input.readUTF8Line()
                    if (responseLine == null) {
                        logger.warn { "No response from ${peer.nodeId} for AppendEntries" }
                        return@withTimeout null
                    }

                    // Deserialize response
                    val response = json.decodeFromString<RaftRPC>(responseLine)
                    if (response !is RaftRPC.AppendEntriesResponse) {
                        logger.warn { "Unexpected response type from ${peer.nodeId}: ${response::class.simpleName}" }
                        return@withTimeout null
                    }

                    logger.trace { "Received AppendEntries response from ${peer.nodeId}: $response" }
                    response

                } finally {
                    socket.close()
                }
            }
        } catch (e: Exception) {
            logger.trace(e) { "Failed to send AppendEntries to ${peer.nodeId}" }
            null
        }
    }

    /**
     * Close the RPC client and release resources
     */
    fun close() {
        selectorManager.close()
    }
}