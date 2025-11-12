package com.raft.rpc

import com.raft.core.*

// RequestVote RPC - invoked by candidates to gather votes
data class RequestVoteRequest(
    val term: Term,
    val candidateId: NodeId,
    val lastLogIndex: LogIndex,
    val lastLogTerm: Term
)

data class RequestVoteResponse(
    val term: Term,
    val voteGranted: Boolean
)

// AppendEntries RPC - invoked by leader to replicate log entries and heartbeat
data class AppendEntriesRequest(
    val term: Term,
    val leaderId: NodeId,
    val prevLogIndex: LogIndex,
    val prevLogTerm: Term,
    val entries: List<LogEntry>,
    val leaderCommit: LogIndex
)

data class AppendEntriesResponse(
    val term: Term,
    val success: Boolean,
    val matchIndex: LogIndex = 0
)

// InstallSnapshot RPC - invoked by leader to send snapshot to slow follower
data class InstallSnapshotRequest(
    val term: Term,
    val leaderId: NodeId,
    val lastIncludedIndex: LogIndex,
    val lastIncludedTerm: Term,
    val data: ByteArray
) {
    override fun equals(other: Any?) = other is InstallSnapshotRequest &&
        term == other.term &&
        leaderId == other.leaderId &&
        lastIncludedIndex == other.lastIncludedIndex &&
        lastIncludedTerm == other.lastIncludedTerm &&
        data.contentEquals(other.data)

    override fun hashCode() = 31 * (31 * (31 * (31 * term.hashCode() +
        leaderId.hashCode()) + lastIncludedIndex.hashCode()) +
        lastIncludedTerm.hashCode()) + data.contentHashCode()
}

data class InstallSnapshotResponse(
    val term: Term
)
