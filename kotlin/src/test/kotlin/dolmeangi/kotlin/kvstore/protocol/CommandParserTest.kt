package dolmeangi.kotlin.kvstore.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CommandParserTest : FunSpec({

    test("parse BEGIN command") {
        val command = CommandParser.parse("BEGIN")
        command shouldBe Command.Begin
    }

    test("parse BEGIN is case insensitive") {
        CommandParser.parse("begin") shouldBe Command.Begin
        CommandParser.parse("Begin") shouldBe Command.Begin
        CommandParser.parse("BeGiN") shouldBe Command.Begin
    }

    test("parse COMMIT command") {
        val command = CommandParser.parse("COMMIT :1001")
        command shouldBe Command.Commit(TransactionId(1001))
    }

    test("parse ABORT command") {
        val command = CommandParser.parse("ABORT :1001")
        command shouldBe Command.Abort(TransactionId(1001))
    }

    test("parse GET command") {
        val command = CommandParser.parse("GET :1001 user:alice")
        command shouldBe Command.Get(TransactionId(1001), "user:alice")
    }

    test("parse PUT command") {
        val command = CommandParser.parse("PUT :1001 counter 42")
        command shouldBe Command.Put(TransactionId(1001), "counter", "42")
    }

    test("parse PUT command with value containing spaces") {
        val command = CommandParser.parse("PUT :1001 user:name Alice Smith")
        command shouldBe Command.Put(TransactionId(1001), "user:name", "Alice Smith")
    }

    test("parse DELETE command") {
        val command = CommandParser.parse("DELETE :1001 user:alice")
        command shouldBe Command.Delete(TransactionId(1001), "user:alice")
    }

    test("parse with leading and trailing whitespace") {
        val command = CommandParser.parse("  BEGIN  ")
        command shouldBe Command.Begin
    }

    test("parse fails with empty command") {
        shouldThrow<ParseException> {
            CommandParser.parse("")
        }
    }

    test("parse fails with whitespace only") {
        shouldThrow<ParseException> {
            CommandParser.parse("   ")
        }
    }

    test("parse fails with unknown command") {
        val exception = shouldThrow<ParseException> {
            CommandParser.parse("FOOBAR")
        }
        exception.message shouldContain "Unknown command"
    }

    test("parse fails when transaction ID missing colon prefix") {
        val exception = shouldThrow<ParseException> {
            CommandParser.parse("COMMIT 1001")
        }
        exception.message shouldContain "Transaction ID must start with ':'"
    }

    test("parse fails when transaction ID is not a number") {
        val exception = shouldThrow<ParseException> {
            CommandParser.parse("COMMIT :abc")
        }
        exception.message shouldContain "Invalid transaction ID"
    }

    test("parse fails when COMMIT has wrong argument count") {
        shouldThrow<ParseException> {
            CommandParser.parse("COMMIT")
        }
    }

    test("parse fails when GET has wrong argument count") {
        shouldThrow<ParseException> {
            CommandParser.parse("GET :1001")
        }
    }

    test("parse fails when PUT has insufficient arguments") {
        shouldThrow<ParseException> {
            CommandParser.parse("PUT :1001 key")
        }
    }

    test("parse handles negative transaction IDs") {
        val command = CommandParser.parse("COMMIT :-1")
        command shouldBe Command.Commit(TransactionId(-1))
    }

    test("parse handles large transaction IDs") {
        val command = CommandParser.parse("COMMIT :9223372036854775807")
        command shouldBe Command.Commit(TransactionId(9223372036854775807L))
    }
})