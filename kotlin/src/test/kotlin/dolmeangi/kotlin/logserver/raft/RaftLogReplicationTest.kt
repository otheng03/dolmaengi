package dolmeangi.kotlin.logserver.raft

import dolmeangi.kotlin.logserver.model.LogEntry
import dolmeangi.kotlin.logserver.model.WriteOperation
import dolmeangi.kotlin.logserver.model.WriteType
import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.PeerInfo
import dolmeangi.kotlin.logserver.raft.persistence.RaftMetadataStore
import dolmeangi.kotlin.logserver.raft.rpc.RaftRPC
import dolmeangi.kotlin.logserver.storage.LogStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files

/**
 * Comprehensive tests for Raft log replication
 *
 * Tests cover:
 * - Follower appending entries
 * - Log conflict detection and resolution
 * - Commit index advancement
 * - Catch-up scenarios
 * - Edge cases
 */
class RaftLogReplicationTest : FunSpec({

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

    // Helper to create a log entry
    fun createLogEntry(index: Long, term: Long, txnId: Long): LogEntry {
        return LogEntry(
            index = index,
            term = term,
            transactionId = txnId,
            commitTimestamp = System.currentTimeMillis(),
            writes = listOf(
                WriteOperation(
                    type = WriteType.PUT,
                    key = "key$txnId",
                    value = "value$txnId",
                    version = txnId
                )
            )
        )
    }

    test("follower accepts AppendEntries with no entries (heartbeat)") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                // Create heartbeat request
                val request = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 0,
                    prevLogTerm = 0,
                    entries = emptyList(),
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should accept heartbeat
                response.success shouldBe true
                response.term shouldBe 1

                // Should update leader
                node.getLeader() shouldBe NodeId(2)
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower appends entries from leader") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                // Create entries to append
                val entries = listOf(
                    createLogEntry(1, 1, 100),
                    createLogEntry(2, 1, 101)
                )

                val json = Json { ignoreUnknownKeys = true }
                val entriesJson = entries.map { json.encodeToString(it) }

                // Create AppendEntries request
                val request = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 0,
                    prevLogTerm = 0,
                    entries = entriesJson,
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should accept entries
                response.success shouldBe true
                response.term shouldBe 1
                response.matchIndex shouldBe 2

                // Verify entries were appended
                storage.getLastIndex() shouldBe 2
                val entry1 = storage.read(1)
                entry1 shouldNotBe null
                entry1!!.transactionId shouldBe 100

                val entry2 = storage.read(2)
                entry2 shouldNotBe null
                entry2!!.transactionId shouldBe 101
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower rejects AppendEntries with old term") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 5
                metadataStore.updateTerm(5)

                // Create request with old term
                val request = RaftRPC.AppendEntriesRequest(
                    term = 3,
                    leaderId = NodeId(2),
                    prevLogIndex = 0,
                    prevLogTerm = 0,
                    entries = emptyList(),
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should reject
                response.success shouldBe false
                response.term shouldBe 5
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower rejects AppendEntries with missing prevLogIndex") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                // Append one entry
                storage.append(100, System.currentTimeMillis(), emptyList())

                // Create request with prevLogIndex=5 (we don't have it)
                val request = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 5,
                    prevLogTerm = 1,
                    entries = emptyList(),
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should reject
                response.success shouldBe false
                response.term shouldBe 1
                response.matchIndex shouldBe 1  // Our last index
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower rejects AppendEntries with term mismatch at prevLogIndex") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 2
                metadataStore.updateTerm(2)

                // Manually append entries with term 1
                val entry1 = createLogEntry(1, 1, 100)
                storage.appendEntries(listOf(entry1))

                val json = Json { ignoreUnknownKeys = true }

                // Create request with prevLogIndex=1 but prevLogTerm=2 (mismatch)
                val entry2 = createLogEntry(2, 2, 101)
                val request = RaftRPC.AppendEntriesRequest(
                    term = 2,
                    leaderId = NodeId(2),
                    prevLogIndex = 1,
                    prevLogTerm = 2,  // Our entry at index 1 has term 1, not 2
                    entries = listOf(json.encodeToString(entry2)),
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should reject
                response.success shouldBe false
                response.matchIndex shouldBe 0  // prevLogIndex - 1
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower truncates conflicting entries") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 2
                metadataStore.updateTerm(2)

                // Append entries with term 1
                val entries = listOf(
                    createLogEntry(1, 1, 100),
                    createLogEntry(2, 1, 101),
                    createLogEntry(3, 1, 102)
                )
                storage.appendEntries(entries)

                storage.getLastIndex() shouldBe 3

                val json = Json { ignoreUnknownKeys = true }

                // Create request with conflicting entry at index 2 (term 2 instead of 1)
                val newEntries = listOf(
                    createLogEntry(2, 2, 200),  // Conflicts with existing entry
                    createLogEntry(3, 2, 201)
                )
                val request = RaftRPC.AppendEntriesRequest(
                    term = 2,
                    leaderId = NodeId(2),
                    prevLogIndex = 1,
                    prevLogTerm = 1,
                    entries = newEntries.map { json.encodeToString(it) },
                    leaderCommit = 0
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                // Should accept and truncate
                response.success shouldBe true
                response.matchIndex shouldBe 3

                // Verify truncation and new entries
                storage.getLastIndex() shouldBe 3
                val entry2 = storage.read(2)
                entry2!!.term shouldBe 2
                entry2.transactionId shouldBe 200

                val entry3 = storage.read(3)
                entry3!!.term shouldBe 2
                entry3.transactionId shouldBe 201
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower advances commit index from leader") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                // Append entries
                val entries = listOf(
                    createLogEntry(1, 1, 100),
                    createLogEntry(2, 1, 101),
                    createLogEntry(3, 1, 102)
                )
                storage.appendEntries(entries)

                // Initially commitIndex is 0
                node.getCommitIndex() shouldBe 0

                val json = Json { ignoreUnknownKeys = true }

                // Leader tells us to commit up to index 2
                val request = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 3,
                    prevLogTerm = 1,
                    entries = emptyList(),
                    leaderCommit = 2
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                response.success shouldBe true

                // CommitIndex should advance to 2
                node.getCommitIndex() shouldBe 2
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower does not advance commit index beyond last new entry") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                // Append only 2 entries
                val entries = listOf(
                    createLogEntry(1, 1, 100),
                    createLogEntry(2, 1, 101)
                )
                storage.appendEntries(entries)

                // Leader says leaderCommit=10, but we only have up to index 2
                val request = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 2,
                    prevLogTerm = 1,
                    entries = emptyList(),
                    leaderCommit = 10
                )

                // Handle request
                val response = node.handleAppendEntries(request)

                response.success shouldBe true

                // CommitIndex should only advance to our last index (2)
                node.getCommitIndex() shouldBe 2
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }

    test("follower appends entries incrementally") {
        val (node, storage, metadataStore) = createTestNode()

        try {
            runTest {
                // Set term to 1
                metadataStore.updateTerm(1)

                val json = Json { ignoreUnknownKeys = true }

                // First batch
                val batch1 = listOf(createLogEntry(1, 1, 100))
                val request1 = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 0,
                    prevLogTerm = 0,
                    entries = batch1.map { json.encodeToString(it) },
                    leaderCommit = 0
                )
                val response1 = node.handleAppendEntries(request1)
                response1.success shouldBe true
                storage.getLastIndex() shouldBe 1

                // Second batch
                val batch2 = listOf(
                    createLogEntry(2, 1, 101),
                    createLogEntry(3, 1, 102)
                )
                val request2 = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 1,
                    prevLogTerm = 1,
                    entries = batch2.map { json.encodeToString(it) },
                    leaderCommit = 1
                )
                val response2 = node.handleAppendEntries(request2)
                response2.success shouldBe true
                storage.getLastIndex() shouldBe 3
                node.getCommitIndex() shouldBe 1

                // Third batch
                val batch3 = listOf(createLogEntry(4, 1, 103))
                val request3 = RaftRPC.AppendEntriesRequest(
                    term = 1,
                    leaderId = NodeId(2),
                    prevLogIndex = 3,
                    prevLogTerm = 1,
                    entries = batch3.map { json.encodeToString(it) },
                    leaderCommit = 3
                )
                val response3 = node.handleAppendEntries(request3)
                response3.success shouldBe true
                storage.getLastIndex() shouldBe 4
                node.getCommitIndex() shouldBe 3
            }
        } finally {
            node.close()
            storage.close()
            metadataStore.close()
        }
    }
})