package com.raft.transport

import com.raft.core.NodeId
import com.raft.node.RaftNode
import com.raft.node.RaftTransport
import com.raft.rpc.*
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory transport for testing RAFT nodes in a single JVM.
 * Simulates network delays and partitions.
 */
class InMemoryTransport(
    private val networkDelayMs: Long = 5,
    private val dropRate: Double = 0.0
) : RaftTransport {
    private val nodes = ConcurrentHashMap<NodeId, RaftNode>()
    private val partitions = mutableSetOf<Pair<NodeId, NodeId>>()

    fun register(nodeId: NodeId, node: RaftNode) {
        nodes[nodeId] = node
    }

    fun partition(from: NodeId, to: NodeId) {
        partitions.add(from to to)
    }

    fun healPartition(from: NodeId, to: NodeId) {
        partitions.remove(from to to)
    }

    fun healAll() {
        partitions.clear()
    }

    private fun isPartitioned(from: NodeId, to: NodeId) =
        (from to to) in partitions || (to to from) in partitions

    private suspend fun simulateNetwork() {
        if (networkDelayMs > 0) {
            delay(networkDelayMs)
        }
    }

    private fun shouldDrop() = dropRate > 0 && Math.random() < dropRate

    override suspend fun sendRequestVote(
        nodeId: NodeId,
        request: RequestVoteRequest
    ): RequestVoteResponse? {
        if (isPartitioned(request.candidateId, nodeId) || shouldDrop()) return null

        simulateNetwork()
        return nodes[nodeId]?.handleRequestVote(request)
    }

    override suspend fun sendAppendEntries(
        nodeId: NodeId,
        request: AppendEntriesRequest
    ): AppendEntriesResponse? {
        if (isPartitioned(request.leaderId, nodeId) || shouldDrop()) return null

        simulateNetwork()
        return nodes[nodeId]?.handleAppendEntries(request)
    }

    override suspend fun sendInstallSnapshot(
        nodeId: NodeId,
        request: InstallSnapshotRequest
    ): InstallSnapshotResponse? {
        if (isPartitioned(request.leaderId, nodeId) || shouldDrop()) return null

        simulateNetwork()
        // Snapshot not implemented yet
        return InstallSnapshotResponse(0)
    }
}
