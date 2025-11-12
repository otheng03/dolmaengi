package com.raft.node

import com.raft.core.*
import com.raft.rpc.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Main RAFT node implementation.
 * Handles leader election, log replication, and state machine application.
 */
class RaftNode(
    config: ClusterConfig,
    private val stateMachine: StateMachine,
    private val transport: RaftTransport
) {
    private val state = RaftState(config)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var running = false

    fun start() {
        running = true
        scope.launch { electionTimerLoop() }
        scope.launch { applyCommittedEntries() }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    // Leader election timer loop
    private suspend fun electionTimerLoop() {
        while (running) {
            delay(50) // Check every 50ms

            when {
                state.role == RaftRole.LEADER && state.shouldSendHeartbeat() -> {
                    sendHeartbeats()
                }
                state.role != RaftRole.LEADER && state.isElectionTimeout() -> {
                    startElection()
                }
            }
        }
    }

    // Start a new election
    private suspend fun startElection() {
        state.becomeCandidate()

        val request = RequestVoteRequest(
            term = state.persistent.currentTerm,
            candidateId = state.config.nodeId,
            lastLogIndex = state.persistent.lastLogIndex(),
            lastLogTerm = state.persistent.lastLogTerm()
        )

        var votes = 1 // Vote for self
        val majority = (state.config.peers.size + 1) / 2 + 1

        // Request votes from all peers in parallel
        state.config.peers.map { peerId ->
            scope.async {
                transport.sendRequestVote(peerId, request)?.let { response ->
                    handleRequestVoteResponse(response)
                    if (response.voteGranted) 1 else 0
                } ?: 0
            }
        }.awaitAll().forEach { votes += it }

        // Become leader if won election
        if (votes >= majority && state.role == RaftRole.CANDIDATE) {
            state.becomeLeader()
        }
    }

    // Send heartbeats to all followers
    private suspend fun sendHeartbeats() {
        state.resetElectionTimer()

        state.config.peers.forEach { peerId ->
            scope.launch {
                val nextIndex = state.leader?.nextIndex?.get(peerId) ?: 1
                val prevLogIndex = nextIndex - 1
                val prevLogTerm = state.persistent.getEntry(prevLogIndex)?.term ?: 0

                val entries = state.persistent.getEntriesFrom(nextIndex)

                val request = AppendEntriesRequest(
                    term = state.persistent.currentTerm,
                    leaderId = state.config.nodeId,
                    prevLogIndex = prevLogIndex,
                    prevLogTerm = prevLogTerm,
                    entries = entries,
                    leaderCommit = state.volatile.commitIndex
                )

                transport.sendAppendEntries(peerId, request)?.let { response ->
                    handleAppendEntriesResponse(peerId, request, response)
                }
            }
        }
    }

    // Apply committed entries to state machine
    private suspend fun applyCommittedEntries() {
        while (running) {
            delay(10)

            while (state.volatile.commitIndex > state.volatile.lastApplied) {
                state.volatile.lastApplied++
                state.persistent.getEntry(state.volatile.lastApplied)?.let { entry ->
                    stateMachine.apply(entry.command)
                }
            }
        }
    }

    // Handle RequestVote RPC
    fun handleRequestVote(request: RequestVoteRequest): RequestVoteResponse {
        // Update term if request has higher term
        if (request.term > state.persistent.currentTerm) {
            state.becomeFollower(request.term)
        }

        val voteGranted = request.term == state.persistent.currentTerm &&
            (state.persistent.votedFor == null || state.persistent.votedFor == request.candidateId) &&
            isLogUpToDate(request.lastLogIndex, request.lastLogTerm)

        if (voteGranted) {
            state.persistent.votedFor = request.candidateId
            state.resetElectionTimer()
        }

        return RequestVoteResponse(
            term = state.persistent.currentTerm,
            voteGranted = voteGranted
        )
    }

    // Handle AppendEntries RPC
    fun handleAppendEntries(request: AppendEntriesRequest): AppendEntriesResponse {
        // Update term if request has higher term
        if (request.term > state.persistent.currentTerm) {
            state.becomeFollower(request.term)
        }

        state.resetElectionTimer()
        state.currentLeader = request.leaderId

        // Reply false if term < currentTerm
        if (request.term < state.persistent.currentTerm) {
            return AppendEntriesResponse(state.persistent.currentTerm, false)
        }

        // Reply false if log doesn't contain entry at prevLogIndex matching prevLogTerm
        val prevEntry = state.persistent.getEntry(request.prevLogIndex)
        if (request.prevLogIndex > 0 && (prevEntry == null || prevEntry.term != request.prevLogTerm)) {
            return AppendEntriesResponse(state.persistent.currentTerm, false)
        }

        // Delete conflicting entries and append new ones
        request.entries.forEach { entry ->
            val existing = state.persistent.getEntry(entry.index)
            if (existing != null && existing.term != entry.term) {
                state.persistent.deleteEntriesFrom(entry.index)
            }
        }
        state.persistent.appendEntries(request.entries)

        // Update commit index
        if (request.leaderCommit > state.volatile.commitIndex) {
            state.volatile.commitIndex = minOf(
                request.leaderCommit,
                state.persistent.lastLogIndex()
            )
        }

        return AppendEntriesResponse(
            term = state.persistent.currentTerm,
            success = true,
            matchIndex = state.persistent.lastLogIndex()
        )
    }

    // Handle RequestVote response
    private fun handleRequestVoteResponse(response: RequestVoteResponse) {
        if (response.term > state.persistent.currentTerm) {
            state.becomeFollower(response.term)
        }
    }

    // Handle AppendEntries response
    private fun handleAppendEntriesResponse(
        peerId: NodeId,
        request: AppendEntriesRequest,
        response: AppendEntriesResponse
    ) {
        if (response.term > state.persistent.currentTerm) {
            state.becomeFollower(response.term)
            return
        }

        val leader = state.leader ?: return

        if (response.success) {
            leader.matchIndex[peerId] = response.matchIndex
            leader.nextIndex[peerId] = response.matchIndex + 1

            // Update commit index
            updateCommitIndex()
        } else {
            // Decrement nextIndex and retry
            leader.nextIndex[peerId] = maxOf(1, (leader.nextIndex[peerId] ?: 1) - 1)
        }
    }

    // Update commit index based on majority replication
    private fun updateCommitIndex() {
        val leader = state.leader ?: return

        for (n in (state.volatile.commitIndex + 1)..state.persistent.lastLogIndex()) {
            val entry = state.persistent.getEntry(n) ?: continue
            if (entry.term != state.persistent.currentTerm) continue

            val replicatedCount = 1 + leader.matchIndex.values.count { it >= n }
            val majority = (state.config.peers.size + 1) / 2 + 1

            if (replicatedCount >= majority) {
                state.volatile.commitIndex = n
            }
        }
    }

    // Check if candidate's log is at least as up-to-date as receiver's log
    private fun isLogUpToDate(lastLogIndex: LogIndex, lastLogTerm: Term): Boolean {
        val myLastTerm = state.persistent.lastLogTerm()
        val myLastIndex = state.persistent.lastLogIndex()

        return lastLogTerm > myLastTerm ||
            (lastLogTerm == myLastTerm && lastLogIndex >= myLastIndex)
    }

    // Client API: Propose a command
    suspend fun propose(command: ByteArray): Boolean {
        if (state.role != RaftRole.LEADER) return false

        val entry = LogEntry(
            index = state.persistent.lastLogIndex() + 1,
            term = state.persistent.currentTerm,
            command = command
        )

        state.persistent.appendEntries(listOf(entry))
        return true
    }

    fun isLeader() = state.role == RaftRole.LEADER
    fun getCurrentTerm() = state.persistent.currentTerm
    fun getRole() = state.role
}

// State machine interface
interface StateMachine {
    fun apply(command: ByteArray): ByteArray
}

// Transport layer interface
interface RaftTransport {
    suspend fun sendRequestVote(nodeId: NodeId, request: RequestVoteRequest): RequestVoteResponse?
    suspend fun sendAppendEntries(nodeId: NodeId, request: AppendEntriesRequest): AppendEntriesResponse?
    suspend fun sendInstallSnapshot(nodeId: NodeId, request: InstallSnapshotRequest): InstallSnapshotResponse?
}
