package com.raft.core

import java.util.UUID

// Core identifiers
typealias Term = Long
typealias LogIndex = Long
typealias NodeId = String

// Node state in the cluster
enum class RaftRole { FOLLOWER, CANDIDATE, LEADER }

// Log entry containing a command to be applied to state machine
data class LogEntry(
    val index: LogIndex,
    val term: Term,
    val command: ByteArray
) {
    override fun equals(other: Any?) = other is LogEntry &&
        index == other.index && term == other.term && command.contentEquals(other.command)

    override fun hashCode() = 31 * (31 * index.hashCode() + term.hashCode()) + command.contentHashCode()
}

// Snapshot of state machine at a specific point
data class Snapshot(
    val lastIncludedIndex: LogIndex,
    val lastIncludedTerm: Term,
    val data: ByteArray
) {
    override fun equals(other: Any?) = other is Snapshot &&
        lastIncludedIndex == other.lastIncludedIndex &&
        lastIncludedTerm == other.lastIncludedTerm &&
        data.contentEquals(other.data)

    override fun hashCode() = 31 * (31 * lastIncludedIndex.hashCode() +
        lastIncludedTerm.hashCode()) + data.contentHashCode()
}

// Volatile state on all servers
data class VolatileState(
    var commitIndex: LogIndex = 0,
    var lastApplied: LogIndex = 0
)

// Volatile state on leaders (reinitialized after election)
data class LeaderState(
    val nextIndex: MutableMap<NodeId, LogIndex> = mutableMapOf(),
    val matchIndex: MutableMap<NodeId, LogIndex> = mutableMapOf()
)

// Cluster configuration
data class ClusterConfig(
    val nodeId: NodeId = UUID.randomUUID().toString(),
    val peers: Set<NodeId>,
    val electionTimeoutMs: LongRange = 150L..300L,
    val heartbeatIntervalMs: Long = 50L
)
