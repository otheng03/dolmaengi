package dolmeangi.kotlin.logserver.raft.rpc

import dolmeangi.kotlin.logserver.raft.model.NodeId
import kotlinx.serialization.Serializable

/**
 * Base sealed class for all Raft RPC messages
 *
 * Raft has two main RPC types:
 * - RequestVote: Used during leader election
 * - AppendEntries: Used for log replication and heartbeats
 */
@Serializable
sealed class RaftRPC {
    /**
     * RequestVote RPC - Invoked by candidates to gather votes
     */
    @Serializable
    data class RequestVoteRequest(
        /**
         * Candidate's term
         */
        val term: Long,

        /**
         * Candidate requesting vote
         */
        val candidateId: NodeId,

        /**
         * Index of candidate's last log entry
         */
        val lastLogIndex: Long,

        /**
         * Term of candidate's last log entry
         */
        val lastLogTerm: Long
    ) : RaftRPC()

    /**
     * RequestVote RPC Response
     */
    @Serializable
    data class RequestVoteResponse(
        /**
         * currentTerm, for candidate to update itself
         */
        val term: Long,

        /**
         * true means candidate received vote
         */
        val voteGranted: Boolean
    ) : RaftRPC()

    /**
     * AppendEntries RPC - Invoked by leader to replicate log entries and as heartbeat
     */
    @Serializable
    data class AppendEntriesRequest(
        /**
         * Leader's term
         */
        val term: Long,

        /**
         * Leader ID, so followers can redirect clients
         */
        val leaderId: NodeId,

        /**
         * Index of log entry immediately preceding new ones
         */
        val prevLogIndex: Long,

        /**
         * Term of prevLogIndex entry
         */
        val prevLogTerm: Long,

        /**
         * Log entries to store (empty for heartbeat)
         */
        val entries: List<String> = emptyList(), // JSON-serialized LogEntry objects

        /**
         * Leader's commitIndex
         */
        val leaderCommit: Long
    ) : RaftRPC()

    /**
     * AppendEntries RPC Response
     */
    @Serializable
    data class AppendEntriesResponse(
        /**
         * currentTerm, for leader to update itself
         */
        val term: Long,

        /**
         * true if follower contained entry matching prevLogIndex and prevLogTerm
         */
        val success: Boolean,

        /**
         * Optimization: last matching index for faster backtracking
         */
        val matchIndex: Long = 0
    ) : RaftRPC()
}