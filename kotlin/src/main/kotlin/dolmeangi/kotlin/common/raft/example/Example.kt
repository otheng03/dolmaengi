package com.raft.example

import com.raft.core.ClusterConfig
import com.raft.node.RaftNode
import com.raft.statemachine.KeyValueStore
import com.raft.transport.InMemoryTransport
import kotlinx.coroutines.*

/**
 * Example usage of RAFT cluster with key-value store.
 */
fun main() = runBlocking {
    // Create a 3-node cluster
    val nodeIds = listOf("node1", "node2", "node3")
    val transport = InMemoryTransport(networkDelayMs = 5)

    val nodes = nodeIds.map { nodeId ->
        val config = ClusterConfig(
            nodeId = nodeId,
            peers = nodeIds.filter { it != nodeId }.toSet(),
            electionTimeoutMs = 150L..300L,
            heartbeatIntervalMs = 50L
        )

        val stateMachine = KeyValueStore()
        val node = RaftNode(config, stateMachine, transport)

        transport.register(nodeId, node)
        nodeId to Pair(node, stateMachine)
    }.toMap()

    // Start all nodes
    nodes.values.forEach { (node, _) -> node.start() }

    println("Starting RAFT cluster with ${nodeIds.size} nodes...")

    // Wait for leader election
    delay(1000)

    // Find the leader
    val leader = nodes.values.firstOrNull { (node, _) -> node.isLeader() }?.first

    if (leader != null) {
        println("Leader elected: ${nodes.entries.first { it.value.first == leader }.key}")

        // Propose some commands
        println("\nProposing commands...")
        leader.propose(KeyValueStore.putCommand("key1", "value1"))
        leader.propose(KeyValueStore.putCommand("key2", "value2"))
        leader.propose(KeyValueStore.putCommand("key3", "value3"))

        // Wait for replication
        delay(500)

        // Check all nodes have the same state
        println("\nState on all nodes:")
        nodes.forEach { (nodeId, pair) ->
            val (_, stateMachine) = pair
            println("$nodeId: ${stateMachine.getAll()}")
        }
    } else {
        println("No leader elected!")
    }

    // Stop all nodes
    nodes.values.forEach { (node, _) -> node.stop() }
}
