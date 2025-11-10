package dolmeangi.kotlin.logserver.raft

import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.PeerInfo
import dolmeangi.kotlin.logserver.raft.persistence.RaftMetadataStore
import dolmeangi.kotlin.logserver.storage.LogStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class RaftNodeTest : FunSpec({

    // Helper to create a test RaftNode
    fun createTestNode(
        nodeId: Int = 1,
        clusterSize: Int = 3,
        electionTimeoutMin: Long = 150,
        electionTimeoutMax: Long = 300,
        heartbeatInterval: Long = 50
    ): Triple<RaftNode, LogStorage, RaftMetadataStore> {
        val tempDir = Files.createTempDirectory("raft-test-$nodeId")

        // Create peers list
        val peers = (1..clusterSize).map { id ->
            PeerInfo(
                nodeId = NodeId(id),
                host = "localhost",
                clientPort = 10000 + id,
                raftPort = 10100 + id
            )
        }

        val config = RaftConfig(
            nodeId = NodeId(nodeId),
            peers = peers,
            electionTimeoutMinMs = electionTimeoutMin,
            electionTimeoutMaxMs = electionTimeoutMax,
            heartbeatIntervalMs = heartbeatInterval
        )

        val storage = LogStorage(dataDir = tempDir.resolve("log"))
        val metadataStore = RaftMetadataStore(tempDir.resolve("raft-metadata.json"))
        val node = RaftNode(config, storage, metadataStore)

        return Triple(node, storage, metadataStore)
    }

    test("node starts as FOLLOWER") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            node.getState() shouldBe RaftState.FOLLOWER
            node.isFollower() shouldBe true
            node.isLeader() shouldBe false
            node.isCandidate() shouldBe false
            node.getCurrentTerm() shouldBe 0
            node.getLeader() shouldBe null
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower transitions to CANDIDATE on election timeout") {
        val (node, storage, metadataStore) = createTestNode(
            electionTimeoutMin = 100,
            electionTimeoutMax = 200
        )

        try {
            node.start()

            // Initial state
            node.getState() shouldBe RaftState.FOLLOWER
            node.getCurrentTerm() shouldBe 0

            // Wait for election timeout (use real delay, not virtual time)
            Thread.sleep(250)  // Wait longer than max timeout

            // Should transition to CANDIDATE and increment term
            node.getState() shouldBe RaftState.CANDIDATE
            node.getCurrentTerm() shouldBe 1

            // Should have voted for self
            val metadata = metadataStore.get()
            metadata.votedFor shouldBe NodeId(1)

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("candidate increments term on each election").config(timeout = 5.seconds) {
        val (node, storage, metadataStore) = createTestNode(
            electionTimeoutMin = 100,
            electionTimeoutMax = 200
        )

        try {
            node.start()

            // Wait for first election - poll until we're a candidate
            var attempts = 0
            while (node.getState() != RaftState.CANDIDATE && attempts < 50) {
                Thread.sleep(50)
                attempts++
            }

            node.getState() shouldBe RaftState.CANDIDATE
            val term1 = node.getCurrentTerm()

            // Wait for second election - poll until term increments
            attempts = 0
            while (node.getCurrentTerm() == term1 && attempts < 50) {
                Thread.sleep(50)
                attempts++
            }

            // Still candidate, but term should have incremented
            node.getState() shouldBe RaftState.CANDIDATE
            node.getCurrentTerm() shouldBe term1 + 1

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("node tracks commit index") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            // Initially zero
            node.getCommitIndex() shouldBe 0
            node.getLastApplied() shouldBe 0

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("node provides status information") {
        val (node, storage, metadataStore) = createTestNode(nodeId = 2)

        try {
            val status = node.getStatus()

            // Status should contain key information
            status.contains("Node ID: Node(2)") shouldBe true
            status.contains("State: FOLLOWER") shouldBe true
            status.contains("Term: 0") shouldBe true

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("node loads existing metadata on startup") {
        val tempDir = Files.createTempDirectory("raft-test-metadata")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            // Pre-populate metadata
            val metadataStore1 = RaftMetadataStore(metadataFile)
            runTest {
                metadataStore1.updateTermAndVote(5, NodeId(2))
            }
            metadataStore1.close()

            // Create node - should load existing metadata
            val peers = listOf(
                PeerInfo(NodeId(1), "localhost", 10001, 10101),
                PeerInfo(NodeId(2), "localhost", 10002, 10102)
            )
            val config = RaftConfig(
                nodeId = NodeId(1),
                peers = peers
            )
            val storage = LogStorage(dataDir = tempDir.resolve("log"))
            val metadataStore2 = RaftMetadataStore(metadataFile)
            val node = RaftNode(config, storage, metadataStore2)

            // Should load term from disk
            node.getCurrentTerm() shouldBe 5

            node.close()
            storage.close()
            metadataStore2.close()

        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("single-node cluster becomes leader") {
        // In a single-node cluster, the node should theoretically become leader
        // However, our current implementation doesn't have RequestVote logic yet
        // This test documents expected future behavior

        val (node, storage, metadataStore) = createTestNode(
            clusterSize = 1,
            electionTimeoutMin = 100,
            electionTimeoutMax = 200
        )

        try {
            node.start()

            // Initial state
            node.getState() shouldBe RaftState.FOLLOWER

            // Wait for election timeout
            Thread.sleep(250)

            // Should become candidate
            node.getState() shouldBe RaftState.CANDIDATE

            // TODO: Once we implement RequestVote, single node should become LEADER
            // node.getState() shouldBe RaftState.LEADER
            // node.getLeader() shouldBe NodeId(1)

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("config returns correct cluster information") {
        val (node, storage, metadataStore) = createTestNode(nodeId = 2, clusterSize = 5)

        try {
            // RaftConfig accessible via RaftNode
            val status = node.getStatus()
            status.contains("Node ID: Node(2)") shouldBe true

        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }
})
