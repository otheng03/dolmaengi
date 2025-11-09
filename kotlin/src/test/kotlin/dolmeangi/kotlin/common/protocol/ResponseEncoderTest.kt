package dolmeangi.kotlin.common.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResponseEncoderTest : FunSpec({

    test("encode status OK") {
        val response = Response.Status("OK")
        ResponseEncoder.encode(response) shouldBe "+OK\r\n"
    }

    test("encode status with custom message") {
        val response = Response.Status("QUEUED")
        ResponseEncoder.encode(response) shouldBe "+QUEUED\r\n"
    }

    test("encode error with ERR type") {
        val response = Response.Error(ErrorType.ERR, "Unknown command")
        ResponseEncoder.encode(response) shouldBe "-ERR Unknown command\r\n"
    }

    test("encode error with CONFLICT type") {
        val response = Response.Error(ErrorType.CONFLICT, "Write-write conflict on key 'counter'")
        ResponseEncoder.encode(response) shouldBe "-CONFLICT Write-write conflict on key 'counter'\r\n"
    }

    test("encode error with NOTFOUND type") {
        val response = Response.Error(ErrorType.NOTFOUND, "Transaction not found")
        ResponseEncoder.encode(response) shouldBe "-NOTFOUND Transaction not found\r\n"
    }

    test("encode error with INVALID type") {
        val response = Response.Error(ErrorType.INVALID, "Empty key not allowed")
        ResponseEncoder.encode(response) shouldBe "-INVALID Empty key not allowed\r\n"
    }

    test("encode error with ABORTED type") {
        val response = Response.Error(ErrorType.ABORTED, "Transaction was aborted")
        ResponseEncoder.encode(response) shouldBe "-ABORTED Transaction was aborted\r\n"
    }

    test("encode error with STORAGE type") {
        val response = Response.Error(ErrorType.STORAGE, "Failed to write to WAL")
        ResponseEncoder.encode(response) shouldBe "-STORAGE Failed to write to WAL\r\n"
    }

    test("encode positive integer") {
        val response = Response.Integer(1001)
        ResponseEncoder.encode(response) shouldBe ":1001\r\n"
    }

    test("encode zero integer") {
        val response = Response.Integer(0)
        ResponseEncoder.encode(response) shouldBe ":0\r\n"
    }

    test("encode negative integer") {
        val response = Response.Integer(-1)
        ResponseEncoder.encode(response) shouldBe ":-1\r\n"
    }

    test("encode large integer") {
        val response = Response.Integer(9223372036854775807L)
        ResponseEncoder.encode(response) shouldBe ":9223372036854775807\r\n"
    }

    test("encode simple string") {
        val response = Response.StringValue("hello")
        ResponseEncoder.encode(response) shouldBe "hello\r\n"
    }

    test("encode string with spaces") {
        val response = Response.StringValue("hello world")
        ResponseEncoder.encode(response) shouldBe "hello world\r\n"
    }

    test("encode string with special characters") {
        val response = Response.StringValue("user:123")
        ResponseEncoder.encode(response) shouldBe "user:123\r\n"
    }

    test("encode JSON-like string") {
        val response = Response.StringValue("{\"name\":\"Alice\",\"age\":30}")
        ResponseEncoder.encode(response) shouldBe "{\"name\":\"Alice\",\"age\":30}\r\n"
    }

    test("encode null response") {
        ResponseEncoder.encode(Response.Null) shouldBe "$-1\r\n"
    }

    test("encode fails when status contains CR") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.Status("OK\r"))
        }
    }

    test("encode fails when status contains LF") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.Status("OK\n"))
        }
    }

    test("encode fails when error message contains CR") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.Error(ErrorType.ERR, "Error\rmessage"))
        }
    }

    test("encode fails when error message contains LF") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.Error(ErrorType.ERR, "Error\nmessage"))
        }
    }

    test("encode fails when string contains CR") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.StringValue("hello\rworld"))
        }
    }

    test("encode fails when string contains LF") {
        shouldThrow<IllegalArgumentException> {
            ResponseEncoder.encode(Response.StringValue("hello\nworld"))
        }
    }

    test("encode Response_OK constant") {
        ResponseEncoder.encode(Response.OK) shouldBe "+OK\r\n"
    }
})