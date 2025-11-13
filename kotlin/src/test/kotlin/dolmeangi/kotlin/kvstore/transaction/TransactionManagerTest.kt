package dolmeangi.kotlin.kvstore.transaction

import dolmeangi.kotlin.common.transaction.MockSequencerClient
import dolmeangi.kotlin.kvstore.protocol.TransactionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TransactionManagerTest : FunSpec({

    lateinit var txnManager: TransactionManager

    beforeTest {
        txnManager = TransactionManager(MockSequencerClient())
    }

    test("begin creates a new transaction") {
        val txnId = txnManager.begin()
        (txnId.value > 0) shouldBe true
    }

    test("begin creates unique transaction IDs") {
        val txnId1 = txnManager.begin()
        val txnId2 = txnManager.begin()
        (txnId2.value > txnId1.value) shouldBe true
    }

    test("get returns null for non-existent key") {
        val txnId = txnManager.begin()
        txnManager.get(txnId, "nonexistent") shouldBe null
    }

    test("put and get within same transaction") {
        val txnId = txnManager.begin()

        txnManager.put(txnId, "key1", "value1")
        txnManager.get(txnId, "key1") shouldBe "value1"
    }

    test("commit empty transaction succeeds") {
        val txnId = txnManager.begin()

        val result = txnManager.commit(txnId)
        result shouldBe CommitResult.Success
    }

    test("commit transaction with writes succeeds") {
        val txnId = txnManager.begin()

        txnManager.put(txnId, "key1", "value1")
        val result = txnManager.commit(txnId)

        result shouldBe CommitResult.Success
    }

    test("committed data is visible to new transactions") {
        // Transaction 1: write and commit
        val txn1 = txnManager.begin()
        txnManager.put(txn1, "key1", "value1")
        txnManager.commit(txn1)

        // Transaction 2: should see the committed value
        val txn2 = txnManager.begin()
        txnManager.get(txn2, "key1") shouldBe "value1"
    }

    test("snapshot isolation - transaction does not see concurrent writes") {
        // Transaction 1 starts
        val txn1 = txnManager.begin()

        // Transaction 2 writes and commits
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "key1", "value2")
        txnManager.commit(txn2)

        // Transaction 1 should not see txn2's writes (snapshot isolation)
        txnManager.get(txn1, "key1") shouldBe null
    }

    test("write-write conflict detection") {
        // Initial state
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "counter", "0")
        txnManager.commit(txn0)

        // Transaction 1 reads counter
        val txn1 = txnManager.begin()
        txnManager.get(txn1, "counter") shouldBe "0"

        // Transaction 2 updates counter and commits
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "counter", "1")
        txnManager.commit(txn2)

        // Transaction 1 tries to update counter - should conflict
        txnManager.put(txn1, "counter", "2")
        val result = txnManager.commit(txn1)

        result.shouldBeInstanceOf<CommitResult.Conflict>()
        result.key shouldBe "counter"
    }

    test("delete marks key as deleted") {
        // Create a key
        val txn1 = txnManager.begin()
        txnManager.put(txn1, "key1", "value1")
        txnManager.commit(txn1)

        // Delete the key
        val txn2 = txnManager.begin()
        txnManager.delete(txn2, "key1")
        txnManager.commit(txn2)

        // Key should be deleted
        val txn3 = txnManager.begin()
        txnManager.get(txn3, "key1") shouldBe null
    }

    test("abort transaction discards writes") {
        val txnId = txnManager.begin()
        txnManager.put(txnId, "key1", "value1")
        txnManager.abort(txnId)

        // New transaction should not see aborted writes
        val txn2 = txnManager.begin()
        txnManager.get(txn2, "key1") shouldBe null
    }

    test("get on non-existent transaction throws exception") {
        shouldThrow<TransactionException.NotFound> {
            txnManager.get(TransactionId(999), "key1")
        }
    }

    test("put on non-existent transaction throws exception") {
        shouldThrow<TransactionException.NotFound> {
            txnManager.put(TransactionId(999), "key1", "value1")
        }
    }

    test("commit on non-existent transaction throws exception") {
        shouldThrow<TransactionException.NotFound> {
            txnManager.commit(TransactionId(999))
        }
    }

    test("abort on non-existent transaction throws exception") {
        shouldThrow<TransactionException.NotFound> {
            txnManager.abort(TransactionId(999))
        }
    }

    test("get on aborted transaction throws exception") {
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.get(txnId, "key1")
        }
    }

    test("put on aborted transaction throws exception") {
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.put(txnId, "key1", "value1")
        }
    }

    test("commit on aborted transaction throws exception") {
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.commit(txnId)
        }
    }

    test("get on committed transaction throws exception") {
        val txnId = txnManager.begin()
        txnManager.commit(txnId)

        shouldThrow<TransactionException.AlreadyCommitted> {
            txnManager.get(txnId, "key1")
        }
    }

    test("multiple concurrent transactions without conflicts") {
        // Transaction 1: write key1
        val txn1 = txnManager.begin()
        txnManager.put(txn1, "key1", "value1")

        // Transaction 2: write key2
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "key2", "value2")

        // Both should commit successfully (no conflicts)
        txnManager.commit(txn1) shouldBe CommitResult.Success
        txnManager.commit(txn2) shouldBe CommitResult.Success

        // Verify both writes
        val txn3 = txnManager.begin()
        txnManager.get(txn3, "key1") shouldBe "value1"
        txnManager.get(txn3, "key2") shouldBe "value2"
    }

    // ========================================
    // New tests for FDB Resolver mechanism
    // ========================================

    test("read-write conflict detection - basic case") {
        // Setup: Create initial data
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "account", "100")
        txnManager.commit(txn0)

        // Transaction 1: Read account balance and prepare to update it
        val txn1 = txnManager.begin()
        val balance = txnManager.get(txn1, "account")
        balance shouldBe "100"

        // Transaction 2: Update account balance and commit
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "account", "150")
        txnManager.commit(txn2) shouldBe CommitResult.Success

        // Transaction 1: Try to write based on stale read (should fail - read-write conflict)
        // T1 read "account" at version 1, but T2 wrote to it at version 2
        txnManager.put(txn1, "notes", "transferred")  // T1 writes to a different key
        val result = txnManager.commit(txn1)

        result.shouldBeInstanceOf<CommitResult.Conflict>()
        result.key shouldBe "account"
    }

    test("read-write conflict detection - read without write") {
        // Setup: Create initial data
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "data", "initial")
        txnManager.commit(txn0)

        // Transaction 1: Read data but don't write anything
        val txn1 = txnManager.begin()
        txnManager.get(txn1, "data") shouldBe "initial"

        // Transaction 2: Update data and commit
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "data", "updated")
        txnManager.commit(txn2) shouldBe CommitResult.Success

        // Transaction 1: Try to commit with empty write set (should fail)
        // Even though T1 has no writes, it read data that was modified
        val result = txnManager.commit(txn1)

        // T1 has no writes, so it succeeds (no conflicts to check for writes)
        // This is correct behavior: read-only transactions don't conflict
        result shouldBe CommitResult.Success
    }

    test("no read-write conflict when reading after commit") {
        // Setup: Create initial data
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "data", "v1")
        txnManager.commit(txn0)

        // Transaction 1: Read data
        val txn1 = txnManager.begin()
        txnManager.get(txn1, "data") shouldBe "v1"

        // Transaction 2: Start after T1's read, update data
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "data", "v2")
        txnManager.commit(txn2) shouldBe CommitResult.Success

        // Transaction 3: Start after T2 commits, should see new data
        val txn3 = txnManager.begin()
        txnManager.get(txn3, "data") shouldBe "v2"
        txnManager.commit(txn3) shouldBe CommitResult.Success
    }

    test("read tracking - reads are recorded in readSet") {
        val txnId = txnManager.begin()

        // Perform some reads
        txnManager.get(txnId, "key1")
        txnManager.get(txnId, "key2")
        txnManager.get(txnId, "key1") // Duplicate read

        // Commit and verify (should succeed with no conflicts)
        txnManager.commit(txnId) shouldBe CommitResult.Success
    }

    test("read-write conflict with multiple keys") {
        // Setup: Create initial data
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "key1", "v1")
        txnManager.put(txn0, "key2", "v2")
        txnManager.commit(txn0)

        // Transaction 1: Read both keys
        val txn1 = txnManager.begin()
        txnManager.get(txn1, "key1") shouldBe "v1"
        txnManager.get(txn1, "key2") shouldBe "v2"

        // Transaction 2: Update key1 and commit
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "key1", "v1-updated")
        txnManager.commit(txn2) shouldBe CommitResult.Success

        // Transaction 1: Write to key2 (should fail - conflict on key1)
        txnManager.put(txn1, "key2", "v2-updated")
        val result = txnManager.commit(txn1)

        result.shouldBeInstanceOf<CommitResult.Conflict>()
        result.key shouldBe "key1"
    }

    test("lastCommit tracks most recent write per key") {
        // Transaction 1: Write key
        val txn1 = txnManager.begin()
        txnManager.put(txn1, "counter", "1")
        txnManager.commit(txn1)

        // Transaction 2: Update key
        val txn2 = txnManager.begin()
        txnManager.put(txn2, "counter", "2")
        txnManager.commit(txn2)

        // Transaction 3: Update key again
        val txn3 = txnManager.begin()
        txnManager.put(txn3, "counter", "3")
        txnManager.commit(txn3)

        // Transaction 4: Should see latest value
        val txn4 = txnManager.begin()
        txnManager.get(txn4, "counter") shouldBe "3"
    }

    test("read-write conflict prevents lost updates") {
        // Classic lost update scenario
        val txn0 = txnManager.begin()
        txnManager.put(txn0, "balance", "100")
        txnManager.commit(txn0)

        // Two transactions try to add 50 to balance
        val txn1 = txnManager.begin()
        val balance1 = txnManager.get(txn1, "balance")?.toInt() ?: 0

        val txn2 = txnManager.begin()
        val balance2 = txnManager.get(txn2, "balance")?.toInt() ?: 0

        // T1 commits first
        txnManager.put(txn1, "balance", (balance1 + 50).toString())
        txnManager.commit(txn1) shouldBe CommitResult.Success

        // T2 should fail (would cause lost update)
        txnManager.put(txn2, "balance", (balance2 + 50).toString())
        val result = txnManager.commit(txn2)

        result.shouldBeInstanceOf<CommitResult.Conflict>()

        // Verify final balance is 150 (not 150 from lost update)
        val txn3 = txnManager.begin()
        txnManager.get(txn3, "balance") shouldBe "150"
    }
})
