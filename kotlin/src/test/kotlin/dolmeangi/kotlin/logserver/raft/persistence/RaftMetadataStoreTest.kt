package dolmeangi.kotlin.logserver.raft.persistence

import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.RaftMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists

class RaftMetadataStoreTest : FunSpec({

    test("initialize with default metadata when file doesn't exist") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            // Should initialize with term=0, votedFor=null
            val metadata = store.get()
            metadata.currentTerm shouldBe 0
            metadata.votedFor.shouldBeNull()

            // File should be created
            metadataFile.exists() shouldBe true

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("load existing metadata from disk") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            // Create store and update metadata
            val store1 = RaftMetadataStore(metadataFile)
            runTest {
                store1.updateTermAndVote(5, NodeId(2))
            }
            store1.close()

            // Reopen and verify metadata persisted
            val store2 = RaftMetadataStore(metadataFile)
            val metadata = store2.get()
            metadata.currentTerm shouldBe 5
            metadata.votedFor shouldBe NodeId(2)

            store2.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("update term clears votedFor") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            // Set term and vote
            runTest {
                store.updateTermAndVote(5, NodeId(2))
            }
            store.get().votedFor shouldBe NodeId(2)

            // Update to new term - should clear vote
            runTest {
                store.updateTerm(6)
            }

            val metadata = store.get()
            metadata.currentTerm shouldBe 6
            metadata.votedFor.shouldBeNull()

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("record vote in current term") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            runTest {
                store.updateTerm(5)
                store.recordVote(NodeId(3))
            }

            val metadata = store.get()
            metadata.currentTerm shouldBe 5
            metadata.votedFor shouldBe NodeId(3)

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("cannot decrease term") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            runTest {
                store.updateTerm(10)
            }

            // Attempting to decrease term should fail
            shouldThrow<IllegalArgumentException> {
                runTest {
                    store.updateTerm(5)
                }
            }

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("metadata persists across process restart") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            // First process: write metadata
            runTest {
                val store1 = RaftMetadataStore(metadataFile)
                store1.updateTermAndVote(42, NodeId(7))
                store1.close()
            }

            // Second process: read metadata
            runTest {
                val store2 = RaftMetadataStore(metadataFile)
                val metadata = store2.get()

                metadata.currentTerm shouldBe 42
                metadata.votedFor shouldBe NodeId(7)

                store2.close()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("concurrent updates are safe") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            // Multiple coroutines updating sequentially
            runTest {
                store.updateTerm(1)
                store.recordVote(NodeId(1))
                store.updateTerm(2)
                store.recordVote(NodeId(2))
                store.updateTerm(3)
            }

            // Final state should be consistent
            val metadata = store.get()
            metadata.currentTerm shouldBe 3
            metadata.votedFor.shouldBeNull() // Cleared when term updated to 3

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("updateTermAndVote atomically updates both fields") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            runTest {
                store.updateTermAndVote(99, NodeId(5))
            }

            val metadata = store.get()
            metadata.currentTerm shouldBe 99
            metadata.votedFor shouldBe NodeId(5)

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    test("getCurrentTerm returns current term") {
        val tempDir = Files.createTempDirectory("raft-metadata-test")
        val metadataFile = tempDir.resolve("raft-metadata.json")

        try {
            val store = RaftMetadataStore(metadataFile)

            store.getCurrentTerm() shouldBe 0

            runTest {
                store.updateTerm(15)
            }

            store.getCurrentTerm() shouldBe 15

            store.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})