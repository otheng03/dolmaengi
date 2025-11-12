package com.raft.core

import kotlin.random.Random

/**
 * Complete state of a RAFT node.
 */
class RaftState(val config: ClusterConfig) {
    val persistent = PersistentState()
    val volatile = VolatileState()

    var role: RaftRole = RaftRole.FOLLOWER
        private set

    var leader: LeaderState? = null
        private set

    var currentLeader: NodeId? = null

    private var lastHeartbeatTime = System.currentTimeMillis()
    private val electionTimeout = Random.nextLong(
        config.electionTimeoutMs.first,
        config.electionTimeoutMs.last
    )

    fun becomeFollower(term: Term) {
        persistent.currentTerm = term
        persistent.votedFor = null
        role = RaftRole.FOLLOWER
        leader = null
    }

    fun becomeCandidate() {
        persistent.currentTerm++
        persistent.votedFor = config.nodeId
        role = RaftRole.CANDIDATE
        leader = null
        resetElectionTimer()
    }

    fun becomeLeader() {
        role = RaftRole.LEADER
        leader = LeaderState().apply {
            config.peers.forEach { peerId ->
                nextIndex[peerId] = persistent.lastLogIndex() + 1
                matchIndex[peerId] = 0
            }
        }
        resetElectionTimer()
    }

    fun resetElectionTimer() {
        lastHeartbeatTime = System.currentTimeMillis()
    }

    fun isElectionTimeout(): Boolean =
        System.currentTimeMillis() - lastHeartbeatTime >= electionTimeout

    fun shouldSendHeartbeat(): Boolean =
        System.currentTimeMillis() - lastHeartbeatTime >= config.heartbeatIntervalMs
}
