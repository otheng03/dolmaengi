package dolmeangi.kotlin.logserver.raft

import dolmeangi.kotlin.logserver.model.LogEntry
import dolmeangi.kotlin.logserver.model.WriteOperation
import dolmeangi.kotlin.logserver.model.WriteType
import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.PeerInfo
import dolmeangi.kotlin.logserver.raft.persistence.RaftMetadataStore
import dolmeangi.kotlin.logserver.raft.rpc.RaftRPCHandler
import dolmeangi.kotlin.logserver.storage.LogStorage
import dolmeangi.kotlin.common.network.TCPServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import java.nio.file.Files

/**
 * Integration tests for multi-node Raft cluster
 *
 * Tests cover:
 * - 3-node cluster setup
 * - Leader election across nodes
 * - Log replication to multiple followers
 * - Majority consensus
 * - Node failure scenarios
 */
class RaftClusterTest : FunSpec({

    // Helper to create a Raft node with TCP server
    data class TestNode(
        val nodeId: Int,
        val raftNode: RaftNode,
        val storage: LogStorage,
        val metadataStore: RaftMetadataStore,
        val tcpServer: TCPServer,
        val tempDir: java.nio.file.Path
    ) : AutoCloseable {
        override fun close() {
            kotlinx.coroutines.runBlocking {
                tcpServer.stop()
            }
            raftNode.close()
            storage.close()
            metadataStore.close()
            tempDir.toFile().deleteRecursively()
        }
    }

    fun createClusterNode(
        nodeId: Int,
        peers: List<PeerInfo>,
        electionTimeoutMin: Long = 150,
        electionTimeoutMax: Long = 300
    ): TestNode {
        val tempDir = Files.createTempDirectory("raft-cluster-test-$nodeId")

        val config = RaftConfig(
            nodeId = NodeId(nodeId),
            peers = peers,
            electionTimeoutMinMs = electionTimeoutMin,
            electionTimeoutMaxMs = electionTimeoutMax,
            heartbeatIntervalMs = 50
        )

        val storage = LogStorage(dataDir = tempDir.resolve("log"))
        val metadataStore = RaftMetadataStore(tempDir.resolve("raft-metadata.json"))
        val raftNode = RaftNode(config, storage, metadataStore)

        // Create TCP server for Raft RPCs
        val rpcHandler = RaftRPCHandler(raftNode)
        val peerInfo = peers.find { it.nodeId == NodeId(nodeId) }!!
        val serverConfig = dolmeangi.kotlin.common.network.ServerConfig(
            host = peerInfo.host,
            port = peerInfo.raftPort
        )
        val tcpServer = TCPServer(serverConfig, rpcHandler)

        return TestNode(nodeId, raftNode, storage, metadataStore, tcpServer, tempDir)
    }

    test("3-node cluster elects a leader").config(timeout = kotlin.time.Duration.parse("30s")) {
        // Define cluster peers
        val peers = listOf(
            PeerInfo(NodeId(1), "localhost", 20001, 20101),
            PeerInfo(NodeId(2), "localhost", 20002, 20102),
            PeerInfo(NodeId(3), "localhost", 20003, 20103)
        )

        // Create 3 nodes
        val node1 = createClusterNode(1, peers)
        val node2 = createClusterNode(2, peers)
        val node3 = createClusterNode(3, peers)

        try {
            // Start TCP servers
            kotlinx.coroutines.runBlocking {
                node1.tcpServer.start()
                node2.tcpServer.start()
                node3.tcpServer.start()
            }

            // Give servers time to start listening
            delay(100)

            println("TCP servers started on ports 20101, 20102, 20103")

            // Start Raft nodes
            node1.raftNode.start()
            node2.raftNode.start()
            node3.raftNode.start()

            println("Raft nodes started, waiting for leader election...")

            // Wait for leader election (up to 5 seconds)
            var leader: TestNode? = null
            var attempts = 0
            while (leader == null && attempts < 100) {
                delay(50)

                val state1 = node1.raftNode.getState()
                val state2 = node2.raftNode.getState()
                val state3 = node3.raftNode.getState()

                if (attempts % 10 == 0) {
                    println("Attempt $attempts: Node1=$state1, Node2=$state2, Node3=$state3")
                }

                when {
                    node1.raftNode.isLeader() -> leader = node1
                    node2.raftNode.isLeader() -> leader = node2
                    node3.raftNode.isLeader() -> leader = node3
                }
                attempts++
            }

            // Should have elected a leader
            leader shouldNotBe null
            println("Leader elected: Node ${leader!!.nodeId} in term ${leader.raftNode.getCurrentTerm()}")

            // Exactly one leader
            val leaderCount = listOf(node1, node2, node3).count { it.raftNode.isLeader() }
            leaderCount shouldBe 1

            // Followers should know the leader
            val allNodes = listOf(node1, node2, node3)
            val followers = allNodes.filter { !it.raftNode.isLeader() }

            // Wait a bit for followers to receive heartbeats
            delay(200)

            followers.forEach { follower ->
                follower.raftNode.getLeader() shouldBe NodeId(leader.nodeId)
            }

        } finally {
            node1.close()
            node2.close()
            node3.close()
        }
    }

    test("leader replicates log entries to followers").config(timeout = kotlin.time.Duration.parse("30s")) {
        // Define cluster peers
        val peers = listOf(
            PeerInfo(NodeId(1), "localhost", 21001, 21101),
            PeerInfo(NodeId(2), "localhost", 21002, 21102),
            PeerInfo(NodeId(3), "localhost", 21003, 21103)
        )

        // Create 3 nodes with faster timeouts for this test
        val node1 = createClusterNode(1, peers, electionTimeoutMin = 200, electionTimeoutMax = 400)
        val node2 = createClusterNode(2, peers, electionTimeoutMin = 500, electionTimeoutMax = 1000)
        val node3 = createClusterNode(3, peers, electionTimeoutMin = 500, electionTimeoutMax = 1000)

        try {
            // Start TCP servers
            kotlinx.coroutines.runBlocking {
                node1.tcpServer.start()
                node2.tcpServer.start()
                node3.tcpServer.start()
            }

            // Start Raft nodes
            node1.raftNode.start()
            node2.raftNode.start()
            node3.raftNode.start()

            // Wait for node1 to become leader (it has shorter timeout)
            var attempts = 0
            while (!node1.raftNode.isLeader() && attempts < 100) {
                delay(50)
                attempts++
            }

            node1.raftNode.isLeader() shouldBe true
            println("Node 1 is leader in term ${node1.raftNode.getCurrentTerm()}")

            // Leader appends entries to its log
            val entry1 = LogEntry(
                index = 1,
                term = node1.raftNode.getCurrentTerm(),
                transactionId = 100,
                commitTimestamp = System.currentTimeMillis(),
                writes = listOf(
                    WriteOperation(WriteType.PUT, "key1", "value1", 100)
                )
            )
            val entry2 = LogEntry(
                index = 2,
                term = node1.raftNode.getCurrentTerm(),
                transactionId = 101,
                commitTimestamp = System.currentTimeMillis(),
                writes = listOf(
                    WriteOperation(WriteType.PUT, "key2", "value2", 101)
                )
            )

            node1.storage.appendEntries(listOf(entry1, entry2))

            println("Leader appended 2 entries to log")

            // Wait for replication (heartbeats run every 50ms)
            delay(500)

            // Check that followers have the entries
            node2.storage.getLastIndex() shouldBe 2
            node3.storage.getLastIndex() shouldBe 2

            val node2Entry1 = node2.storage.read(1)
            node2Entry1 shouldNotBe null
            node2Entry1!!.transactionId shouldBe 100

            val node3Entry1 = node3.storage.read(1)
            node3Entry1 shouldNotBe null
            node3Entry1!!.transactionId shouldBe 100

            println("Followers replicated entries successfully")

            // Wait for commit index to advance
            delay(500)

            // All nodes should have committed the entries
            node1.raftNode.getCommitIndex() shouldBe 2
            node2.raftNode.getCommitIndex() shouldBe 2
            node3.raftNode.getCommitIndex() shouldBe 2

            println("All nodes committed entries")

        } finally {
            node1.close()
            node2.close()
            node3.close()
        }
    }

    test("cluster tolerates single follower failure").config(timeout = kotlin.time.Duration.parse("30s")) {
        // Define cluster peers
        val peers = listOf(
            PeerInfo(NodeId(1), "localhost", 22001, 22101),
            PeerInfo(NodeId(2), "localhost", 22002, 22102),
            PeerInfo(NodeId(3), "localhost", 22003, 22103)
        )

        // Create 3 nodes
        val node1 = createClusterNode(1, peers, electionTimeoutMin = 200, electionTimeoutMax = 400)
        val node2 = createClusterNode(2, peers, electionTimeoutMin = 500, electionTimeoutMax = 1000)
        val node3 = createClusterNode(3, peers, electionTimeoutMin = 500, electionTimeoutMax = 1000)

        try {
            // Start all nodes
            kotlinx.coroutines.runBlocking {
                node1.tcpServer.start()
                node2.tcpServer.start()
                node3.tcpServer.start()
            }

            node1.raftNode.start()
            node2.raftNode.start()
            node3.raftNode.start()

            // Wait for node1 to become leader
            var attempts = 0
            while (!node1.raftNode.isLeader() && attempts < 100) {
                delay(50)
                attempts++
            }

            node1.raftNode.isLeader() shouldBe true
            println("Node 1 is leader")

            // Stop node3 (follower failure)
            kotlinx.coroutines.runBlocking {
                node3.tcpServer.stop()
                node3.raftNode.stop()
            }
            println("Node 3 stopped")

            // Leader should still be able to replicate with majority (2/3)
            val entry = LogEntry(
                index = 1,
                term = node1.raftNode.getCurrentTerm(),
                transactionId = 200,
                commitTimestamp = System.currentTimeMillis(),
                writes = listOf(
                    WriteOperation(WriteType.PUT, "key", "value", 200)
                )
            )

            node1.storage.appendEntries(listOf(entry))

            // Wait for replication
            delay(500)

            // Node2 should have the entry
            node2.storage.getLastIndex() shouldBe 1
            val node2Entry = node2.storage.read(1)
            node2Entry shouldNotBe null
            node2Entry!!.transactionId shouldBe 200

            // Should commit with majority (2/3)
            delay(500)
            node1.raftNode.getCommitIndex() shouldBe 1
            node2.raftNode.getCommitIndex() shouldBe 1

            println("Cluster continues with 2/3 nodes")

        } finally {
            node1.close()
            node2.close()
            node3.close()
        }
    }

    test("new leader is elected after leader failure").config(timeout = kotlin.time.Duration.parse("30s")) {
        // Define cluster peers
        val peers = listOf(
            PeerInfo(NodeId(1), "localhost", 23001, 23101),
            PeerInfo(NodeId(2), "localhost", 23002, 23102),
            PeerInfo(NodeId(3), "localhost", 23003, 23103)
        )

        // Create 3 nodes
        val node1 = createClusterNode(1, peers, electionTimeoutMin = 200, electionTimeoutMax = 400)
        val node2 = createClusterNode(2, peers, electionTimeoutMin = 300, electionTimeoutMax = 600)
        val node3 = createClusterNode(3, peers, electionTimeoutMin = 300, electionTimeoutMax = 600)

        try {
            // Start all nodes
            kotlinx.coroutines.runBlocking {
                node1.tcpServer.start()
                node2.tcpServer.start()
                node3.tcpServer.start()
            }

            node1.raftNode.start()
            node2.raftNode.start()
            node3.raftNode.start()

            // Wait for node1 to become leader
            var attempts = 0
            while (!node1.raftNode.isLeader() && attempts < 100) {
                delay(50)
                attempts++
            }

            node1.raftNode.isLeader() shouldBe true
            val term1 = node1.raftNode.getCurrentTerm()
            println("Node 1 is leader in term $term1")

            // Stop the leader (node1)
            kotlinx.coroutines.runBlocking {
                node1.tcpServer.stop()
                node1.raftNode.stop()
            }
            println("Leader (Node 1) stopped")

            // Wait for new election (election timeout + election time)
            delay(1500)

            // One of the remaining nodes should become leader
            val newLeader = when {
                node2.raftNode.isLeader() -> node2
                node3.raftNode.isLeader() -> node3
                else -> null
            }

            newLeader shouldNotBe null
            val term2 = newLeader!!.raftNode.getCurrentTerm()
            println("New leader elected: Node ${newLeader.nodeId} in term $term2")

            // New term should be higher
            term2 shouldBe term1 + 1

            // Exactly one leader
            val leaderCount = listOf(node2, node3).count { it.raftNode.isLeader() }
            leaderCount shouldBe 1

        } finally {
            node1.close()
            node2.close()
            node3.close()
        }
    }
})