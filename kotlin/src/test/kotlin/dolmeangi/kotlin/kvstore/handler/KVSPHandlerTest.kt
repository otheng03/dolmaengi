package dolmeangi.kotlin.kvstore.handler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class KVSPHandlerTest : FunSpec({

    test("handle BEGIN returns transaction ID") {
        val handler = KVSPPHandler()
        val response = handler.handle("BEGIN")

        response.shouldStartWith(":")
        response shouldContain "\r\n"
    }

    test("handle complete transaction flow") {
        val handler = KVSPPHandler()

        // Begin transaction
        val beginResponse = handler.handle("BEGIN")
        val txnId = beginResponse.trim().substring(1) // Remove ':' prefix and '\r\n'

        // Put a value
        val putResponse = handler.handle("PUT :$txnId key1 value1")
        putResponse shouldBe "+OK\r\n"

        // Get the value
        val getResponse = handler.handle("GET :$txnId key1")
        getResponse shouldBe "value1\r\n"

        // Commit
        val commitResponse = handler.handle("COMMIT :$txnId")
        commitResponse shouldBe "+OK\r\n"
    }

    test("handle GET for non-existent key returns null") {
        val handler = KVSPPHandler()

        val beginResponse = handler.handle("BEGIN")
        val txnId = beginResponse.trim().substring(1)

        val getResponse = handler.handle("GET :$txnId nonexistent")
        getResponse shouldBe "$-1\r\n"
    }

    test("handle DELETE removes key") {
        val handler = KVSPPHandler()

        // Create and commit a key
        val txn1 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txn1 key1 value1")
        handler.handle("COMMIT :$txn1")

        // Delete the key
        val txn2 = handler.handle("BEGIN").trim().substring(1)
        val deleteResponse = handler.handle("DELETE :$txn2 key1")
        deleteResponse shouldBe "+OK\r\n"
        handler.handle("COMMIT :$txn2")

        // Verify it's deleted
        val txn3 = handler.handle("BEGIN").trim().substring(1)
        val getResponse = handler.handle("GET :$txn3 key1")
        getResponse shouldBe "$-1\r\n"
    }

    test("handle ABORT discards changes") {
        val handler = KVSPPHandler()

        // Begin and write
        val txnId = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txnId key1 value1")

        // Abort
        val abortResponse = handler.handle("ABORT :$txnId")
        abortResponse shouldBe "+OK\r\n"

        // Verify changes were discarded
        val txn2 = handler.handle("BEGIN").trim().substring(1)
        val getResponse = handler.handle("GET :$txn2 key1")
        getResponse shouldBe "$-1\r\n"
    }

    test("handle write-write conflict") {
        val handler = KVSPPHandler()

        // Set initial value
        val txn0 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txn0 counter 0")
        handler.handle("COMMIT :$txn0")

        // Transaction 1 starts
        val txn1 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("GET :$txn1 counter")

        // Transaction 2 updates and commits
        val txn2 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txn2 counter 1")
        handler.handle("COMMIT :$txn2")

        // Transaction 1 tries to update - should conflict
        handler.handle("PUT :$txn1 counter 2")
        val commitResponse = handler.handle("COMMIT :$txn1")

        commitResponse.shouldStartWith("-CONFLICT")
        commitResponse shouldContain "counter"
    }

    test("handle unknown command returns error") {
        val handler = KVSPPHandler()
        val response = handler.handle("FOOBAR")

        response.shouldStartWith("-ERR")
        response shouldContain "Unknown command"
    }

    test("handle invalid transaction ID returns error") {
        val handler = KVSPPHandler()
        val response = handler.handle("COMMIT :abc")

        response.shouldStartWith("-ERR")
        response shouldContain "Invalid transaction ID"
    }

    test("handle non-existent transaction returns error") {
        val handler = KVSPPHandler()
        val response = handler.handle("COMMIT :999999")

        response.shouldStartWith("-NOTFOUND")
        response shouldContain "not found"
    }

    test("handle operation on aborted transaction returns error") {
        val handler = KVSPPHandler()

        val txnId = handler.handle("BEGIN").trim().substring(1)
        handler.handle("ABORT :$txnId")

        val response = handler.handle("GET :$txnId key1")
        response.shouldStartWith("-ABORTED")
    }

    test("handle PUT with value containing spaces") {
        val handler = KVSPPHandler()

        val txnId = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txnId user:name Alice Smith")

        val getResponse = handler.handle("GET :$txnId user:name")
        getResponse shouldBe "Alice Smith\r\n"
    }

    test("handle case insensitive commands") {
        val handler = KVSPPHandler()

        val response1 = handler.handle("begin")
        response1.shouldStartWith(":")

        val response2 = handler.handle("BEGIN")
        response2.shouldStartWith(":")

        val response3 = handler.handle("BeGiN")
        response3.shouldStartWith(":")
    }

    test("handle snapshot isolation") {
        val handler = KVSPPHandler()

        // Transaction 1 starts
        val txn1 = handler.handle("BEGIN").trim().substring(1)

        // Transaction 2 writes and commits
        val txn2 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("PUT :$txn2 key1 value2")
        handler.handle("COMMIT :$txn2")

        // Transaction 1 should not see txn2's writes
        val getResponse = handler.handle("GET :$txn1 key1")
        getResponse shouldBe "$-1\r\n"
    }

    test("handle multiple keys in a single transaction") {
        val handler = KVSPPHandler()

        // Begin transaction
        val txnId = handler.handle("BEGIN").trim().substring(1)

        // Put multiple keys
        handler.handle("PUT :$txnId user:alice {\"name\":\"Alice\",\"age\":30}") shouldBe "+OK\r\n"
        handler.handle("PUT :$txnId user:bob {\"name\":\"Bob\",\"age\":25}") shouldBe "+OK\r\n"
        handler.handle("PUT :$txnId counter 42") shouldBe "+OK\r\n"
        handler.handle("PUT :$txnId config:debug true") shouldBe "+OK\r\n"

        // Get all keys within the same transaction
        handler.handle("GET :$txnId user:alice") shouldBe "{\"name\":\"Alice\",\"age\":30}\r\n"
        handler.handle("GET :$txnId user:bob") shouldBe "{\"name\":\"Bob\",\"age\":25}\r\n"
        handler.handle("GET :$txnId counter") shouldBe "42\r\n"
        handler.handle("GET :$txnId config:debug") shouldBe "true\r\n"

        // Get non-existent key
        handler.handle("GET :$txnId user:charlie") shouldBe "$-1\r\n"

        // Commit
        handler.handle("COMMIT :$txnId") shouldBe "+OK\r\n"

        // Verify all keys are persisted in a new transaction
        val txn2 = handler.handle("BEGIN").trim().substring(1)
        handler.handle("GET :$txn2 user:alice") shouldBe "{\"name\":\"Alice\",\"age\":30}\r\n"
        handler.handle("GET :$txn2 user:bob") shouldBe "{\"name\":\"Bob\",\"age\":25}\r\n"
        handler.handle("GET :$txn2 counter") shouldBe "42\r\n"
        handler.handle("GET :$txn2 config:debug") shouldBe "true\r\n"
    }
})