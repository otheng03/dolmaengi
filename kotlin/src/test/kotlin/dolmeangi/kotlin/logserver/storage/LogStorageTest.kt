package dolmeangi.kotlin.logserver.storage

import dolmeangi.kotlin.logserver.model.WriteOperation
import dolmeangi.kotlin.logserver.model.WriteType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class LogStorageTest : StringSpec({

    fun createTempStorage(): Pair<LogStorage, java.nio.file.Path> {
        val tempDir = Files.createTempDirectory("logserver-test")
        val storage = LogStorage(
            dataDir = tempDir,
            maxSegmentSizeBytes = 1024 * 1024, // 1MB for testing
            maxEntriesPerSegment = 100
        )
        return storage to tempDir
    }

    fun cleanupStorage(storage: LogStorage, tempDir: java.nio.file.Path) {
        storage.close()
        tempDir.deleteRecursively()
    }

    "should start with empty storage" {
        val (storage, tempDir) = createTempStorage()
        try {
            storage.isEmpty() shouldBe true
            storage.getFirstIndex().shouldBeNull()
            storage.getLastIndex().shouldBeNull()
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should append a log entry" {
        val (storage, tempDir) = createTempStorage()
        try {
            val writes = listOf(
                WriteOperation(WriteType.PUT, "key1", "value1", 1)
            )

            val logIndex = storage.append(
                transactionId = 1001,
                commitTimestamp = 1234567890,
                writes = writes
            )

            logIndex shouldBe 1
            storage.isEmpty() shouldBe false
            storage.getFirstIndex() shouldBe 1
            storage.getLastIndex() shouldBe 1
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should append multiple log entries" {
        val (storage, tempDir) = createTempStorage()
        try {
            val writes1 = listOf(
                WriteOperation(WriteType.PUT, "key1", "value1", 1)
            )
            val writes2 = listOf(
                WriteOperation(WriteType.PUT, "key2", "value2", 2),
                WriteOperation(WriteType.DELETE, "key3", null, 3)
            )

            val index1 = storage.append(1001, 1000, writes1)
            val index2 = storage.append(1002, 2000, writes2)

            index1 shouldBe 1
            index2 shouldBe 2
            storage.getFirstIndex() shouldBe 1
            storage.getLastIndex() shouldBe 2
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should read a log entry by index" {
        val (storage, tempDir) = createTempStorage()
        try {
            val writes = listOf(
                WriteOperation(WriteType.PUT, "key1", "value1", 1)
            )

            storage.append(1001, 1234567890, writes)

            val entry = storage.read(1)
            entry.shouldNotBeNull()
            entry.index shouldBe 1
            entry.term shouldBe 1
            entry.transactionId shouldBe 1001
            entry.commitTimestamp shouldBe 1234567890
            entry.writes shouldContainExactly writes
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should return null for non-existent log index" {
        val (storage, tempDir) = createTempStorage()
        try {
            val entry = storage.read(999)
            entry.shouldBeNull()
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should read log entries in range" {
        val (storage, tempDir) = createTempStorage()
        try {
            // Append 5 entries
            for (i in 1..5) {
                storage.append(
                    transactionId = 1000L + i,
                    commitTimestamp = 1000L * i,
                    writes = listOf(WriteOperation(WriteType.PUT, "key$i", "value$i", i.toLong()))
                )
            }

            val entries = storage.readRange(2, 4)
            entries.size shouldBe 3
            entries[0].index shouldBe 2
            entries[1].index shouldBe 3
            entries[2].index shouldBe 4
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should read log entries from index onwards" {
        val (storage, tempDir) = createTempStorage()
        try {
            // Append 5 entries
            for (i in 1..5) {
                storage.append(
                    transactionId = 1000L + i,
                    commitTimestamp = 1000L * i,
                    writes = listOf(WriteOperation(WriteType.PUT, "key$i", "value$i", i.toLong()))
                )
            }

            val entries = storage.readFrom(3)
            entries.size shouldBe 3
            entries[0].index shouldBe 3
            entries[1].index shouldBe 4
            entries[2].index shouldBe 5
        } finally {
            cleanupStorage(storage, tempDir)
        }
    }

    "should persist and reload log entries" {
        val tempDir = Files.createTempDirectory("logserver-test")
        try {
            // Create storage and append entries
            run {
                val storage = LogStorage(tempDir)
                storage.append(1001, 1000, listOf(WriteOperation(WriteType.PUT, "key1", "value1", 1)))
                storage.append(1002, 2000, listOf(WriteOperation(WriteType.PUT, "key2", "value2", 2)))
                storage.close()
            }

            // Reopen storage and verify entries are still there
            run {
                val storage = LogStorage(tempDir)
                storage.isEmpty() shouldBe false
                storage.getFirstIndex() shouldBe 1
                storage.getLastIndex() shouldBe 2

                val entry1 = storage.read(1)
                entry1.shouldNotBeNull()
                entry1.transactionId shouldBe 1001

                val entry2 = storage.read(2)
                entry2.shouldNotBeNull()
                entry2.transactionId shouldBe 1002

                storage.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
})