package dolmeangi.kotlin.common.raft

import com.raft.core.ClusterConfig
import com.raft.node.RaftNode
import com.raft.statemachine.KeyValueStore
import com.raft.transport.InMemoryTransport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

class RaftNodeTest : FunSpec({

    test("should elect a leader") {
        val (nodes, _, _) = createCluster(3)

        // Wait for leader election (with timeout)
        waitForLeader(nodes, timeoutMs = 3000)

        val leaders = nodes.values.count { it.first.isLeader() }
        leaders shouldBeExactly 1 // Should have exactly one leader

        cleanup(nodes)
    }

    test("should replicate log entries") {
        val (nodes, stateMachines, _) = createCluster(3)

        // Wait for leader election
        waitForLeader(nodes, timeoutMs = 3000)

        val leader = nodes.values.first { it.first.isLeader() }.first

        // Propose commands
        leader.propose(KeyValueStore.putCommand("foo", "bar"))
        leader.propose(KeyValueStore.putCommand("hello", "world"))

        // Wait for replication with timeout
        waitForCondition(timeoutMs = 2000) {
            stateMachines.values.all { sm ->
                sm.get("foo") == "bar" && sm.get("hello") == "world"
            }
        }

        // All nodes should have the same state
        stateMachines.values.forEach { sm ->
            sm.get("foo") shouldBe "bar"
            sm.get("hello") shouldBe "world"
        }

        cleanup(nodes)
    }

    test("should handle network partition") {
        val (nodes, stateMachines, transport) = createCluster(5)

        // Wait for initial leader election
        waitForLeader(nodes, timeoutMs = 3000)

        val leader = nodes.values.first { it.first.isLeader() }.first
        val leaderId = nodes.entries.first { it.value.first == leader }.key

        // Propose a command
        leader.propose(KeyValueStore.putCommand("key1", "value1"))

        // Wait for replication
        waitForCondition(timeoutMs = 2000) {
            stateMachines.values.any { sm -> sm.get("key1") == "value1" }
        }

        // Partition the leader
        nodes.keys.filter { it != leaderId }.forEach { nodeId ->
            transport.partition(leaderId, nodeId)
        }

        // Wait for new leader election in majority partition
        waitForCondition(timeoutMs = 3000) {
            val newLeaders = nodes.entries.filter { it.key != leaderId }
                .count { it.value.first.isLeader() }
            newLeaders >= 1
        }

        // Should have a new leader
        val newLeaders = nodes.entries.filter { it.key != leaderId }
            .count { it.value.first.isLeader() }
        newLeaders shouldBeExactly 1 // Should elect a new leader after partition

        // Heal partition
        transport.healAll()

        // Wait for leader consensus
        waitForCondition(timeoutMs = 3000) {
            nodes.values.count { it.first.isLeader() } == 1
        }

        // Should have exactly one leader after healing
        val finalLeaders = nodes.values.count { it.first.isLeader() }
        finalLeaders shouldBeExactly 1 // Should have consistent leader after healing

        cleanup(nodes)
    }
}) {
    companion object {
        private fun createCluster(size: Int): Triple<
            Map<String, Pair<RaftNode, KeyValueStore>>,
            Map<String, KeyValueStore>,
            InMemoryTransport
        > {
            val nodeIds = (1..size).map { "node$it" }
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
                node.start()

                nodeId to Pair(node, stateMachine)
            }.toMap()

            val stateMachines = nodes.mapValues { it.value.second }

            return Triple(nodes, stateMachines, transport)
        }

        private fun cleanup(nodes: Map<String, Pair<RaftNode, KeyValueStore>>) {
            nodes.values.forEach { it.first.stop() }
        }

        private suspend fun waitForLeader(
            nodes: Map<String, Pair<RaftNode, KeyValueStore>>,
            timeoutMs: Long = 5000
        ) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val leaders = nodes.values.count { it.first.isLeader() }
                if (leaders > 0) return
                delay(50)
            }

            // Debug info
            val roles = nodes.mapValues { it.value.first.getRole() }
            val terms = nodes.mapValues { it.value.first.getCurrentTerm() }
            throw AssertionError("No leader elected within ${timeoutMs}ms. Roles: $roles, Terms: $terms")
        }

        private suspend fun waitForCondition(
            timeoutMs: Long = 3000,
            checkIntervalMs: Long = 50,
            condition: () -> Boolean
        ) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (condition()) return
                delay(checkIntervalMs)
            }
            throw AssertionError("Condition not met within ${timeoutMs}ms")
        }
    }
}
