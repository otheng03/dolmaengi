package dolmeangi.kotlin.logserver.raft.rpc

import dolmeangi.kotlin.common.network.RequestHandler
import dolmeangi.kotlin.logserver.raft.RaftNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Request handler for Raft RPC messages
 *
 * Handles incoming RequestVote and AppendEntries RPCs from peer nodes.
 * Routes requests to the RaftNode for processing.
 */
class RaftRPCHandler(
    private val raftNode: RaftNode
) : RequestHandler {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(request: String): String {
        return try {
            // Parse RPC message
            val rpc = json.decodeFromString<RaftRPC>(request)

            logger.debug { "Received Raft RPC: ${rpc::class.simpleName}" }

            // Route to appropriate handler
            val response = when (rpc) {
                is RaftRPC.RequestVoteRequest -> handleRequestVote(rpc)
                is RaftRPC.AppendEntriesRequest -> handleAppendEntries(rpc)
                else -> {
                    logger.warn { "Received unexpected RPC type: ${rpc::class.simpleName}" }
                    return "ERROR: Unexpected RPC type"
                }
            }

            // Serialize response
            json.encodeToString<RaftRPC>(response)

        } catch (e: Exception) {
            logger.error(e) { "Error handling Raft RPC: $request" }
            "ERROR: ${e.message}"
        }
    }

    /**
     * Handle RequestVote RPC
     */
    private suspend fun handleRequestVote(request: RaftRPC.RequestVoteRequest): RaftRPC.RequestVoteResponse {
        logger.debug {
            "RequestVote from ${request.candidateId} " +
                    "(term=${request.term}, lastLogIndex=${request.lastLogIndex}, lastLogTerm=${request.lastLogTerm})"
        }

        // Delegate to RaftNode
        return raftNode.handleRequestVote(request)
    }

    /**
     * Handle AppendEntries RPC
     */
    private suspend fun handleAppendEntries(request: RaftRPC.AppendEntriesRequest): RaftRPC.AppendEntriesResponse {
        logger.trace {
            "AppendEntries from ${request.leaderId} " +
                    "(term=${request.term}, prevLogIndex=${request.prevLogIndex}, entries=${request.entries.size})"
        }

        // Delegate to RaftNode
        return raftNode.handleAppendEntries(request)
    }
}