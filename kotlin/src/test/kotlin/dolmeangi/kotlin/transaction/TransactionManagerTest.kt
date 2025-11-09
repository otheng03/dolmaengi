package dolmeangi.kotlin.transaction

import dolmeangi.kotlin.protocol.TransactionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TransactionManagerTest : FunSpec({

    test("begin creates a new transaction") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        (txnId.value > 0) shouldBe true
    }

    test("begin creates unique transaction IDs") {
        val txnManager = TransactionManager()
        val txnId1 = txnManager.begin()
        val txnId2 = txnManager.begin()
        (txnId2.value > txnId1.value) shouldBe true
    }

    test("get returns null for non-existent key") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        txnManager.get(txnId, "nonexistent") shouldBe null
    }

    test("put and get within same transaction") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()

        txnManager.put(txnId, "key1", "value1")
        txnManager.get(txnId, "key1") shouldBe "value1"
    }

    test("commit empty transaction succeeds") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()

        val result = txnManager.commit(txnId)
        result shouldBe CommitResult.Success
    }

    test("commit transaction with writes succeeds") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()

        txnManager.put(txnId, "key1", "value1")
        val result = txnManager.commit(txnId)

        result shouldBe CommitResult.Success
    }

    test("committed data is visible to new transactions") {
        val txnManager = TransactionManager()

        // Transaction 1: write and commit
        val txn1 = txnManager.begin()
        txnManager.put(txn1, "key1", "value1")
        txnManager.commit(txn1)

        // Transaction 2: should see the committed value
        val txn2 = txnManager.begin()
        txnManager.get(txn2, "key1") shouldBe "value1"
    }

    test("snapshot isolation - transaction does not see concurrent writes") {
        val txnManager = TransactionManager()

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
        val txnManager = TransactionManager()

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
        val txnManager = TransactionManager()

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
        val txnManager = TransactionManager()

        val txnId = txnManager.begin()
        txnManager.put(txnId, "key1", "value1")
        txnManager.abort(txnId)

        // New transaction should not see aborted writes
        val txn2 = txnManager.begin()
        txnManager.get(txn2, "key1") shouldBe null
    }

    test("get on non-existent transaction throws exception") {
        val txnManager = TransactionManager()
        shouldThrow<TransactionException.NotFound> {
            txnManager.get(TransactionId(999), "key1")
        }
    }

    test("put on non-existent transaction throws exception") {
        val txnManager = TransactionManager()
        shouldThrow<TransactionException.NotFound> {
            txnManager.put(TransactionId(999), "key1", "value1")
        }
    }

    test("commit on non-existent transaction throws exception") {
        val txnManager = TransactionManager()
        shouldThrow<TransactionException.NotFound> {
            txnManager.commit(TransactionId(999))
        }
    }

    test("abort on non-existent transaction throws exception") {
        val txnManager = TransactionManager()
        shouldThrow<TransactionException.NotFound> {
            txnManager.abort(TransactionId(999))
        }
    }

    test("get on aborted transaction throws exception") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.get(txnId, "key1")
        }
    }

    test("put on aborted transaction throws exception") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.put(txnId, "key1", "value1")
        }
    }

    test("commit on aborted transaction throws exception") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        txnManager.abort(txnId)

        shouldThrow<TransactionException.AlreadyAborted> {
            txnManager.commit(txnId)
        }
    }

    test("get on committed transaction throws exception") {
        val txnManager = TransactionManager()
        val txnId = txnManager.begin()
        txnManager.commit(txnId)

        shouldThrow<TransactionException.AlreadyCommitted> {
            txnManager.get(txnId, "key1")
        }
    }

    test("multiple concurrent transactions without conflicts") {
        val txnManager = TransactionManager()

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
})
