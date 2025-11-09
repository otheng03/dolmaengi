package dolmeangi.kotlin.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LineBufferTest : FunSpec({

    test("should extract single complete line") {
        val buffer = LineBuffer(1024)
        val input = "Hello, World!\r\n".toByteArray()

        val lines = buffer.append(input, input.size)

        lines shouldContainExactly listOf("Hello, World!")
        buffer.size() shouldBe 0
    }

    test("should extract multiple complete lines") {
        val buffer = LineBuffer(1024)
        val input = "First line\r\nSecond line\r\nThird line\r\n".toByteArray()

        val lines = buffer.append(input, input.size)

        lines shouldContainExactly listOf("First line", "Second line", "Third line")
        buffer.size() shouldBe 0
    }

    test("should buffer partial line") {
        val buffer = LineBuffer(1024)
        val input = "Partial".toByteArray()

        val lines = buffer.append(input, input.size)

        lines.shouldBeEmpty()
        buffer.size() shouldBe 7
    }

    test("should handle partial line followed by completion") {
        val buffer = LineBuffer(1024)

        // First chunk: partial line
        val chunk1 = "Hello, ".toByteArray()
        val lines1 = buffer.append(chunk1, chunk1.size)
        lines1.shouldBeEmpty()

        // Second chunk: complete the line
        val chunk2 = "World!\r\n".toByteArray()
        val lines2 = buffer.append(chunk2, chunk2.size)

        lines2 shouldContainExactly listOf("Hello, World!")
        buffer.size() shouldBe 0
    }

    test("should handle CRLF split across chunks") {
        val buffer = LineBuffer(1024)

        // First chunk: ends with CR
        val chunk1 = "Test\r".toByteArray()
        val lines1 = buffer.append(chunk1, chunk1.size)
        lines1.shouldBeEmpty()

        // Second chunk: starts with LF
        val chunk2 = "\nNext line\r\n".toByteArray()
        val lines2 = buffer.append(chunk2, chunk2.size)

        lines2 shouldContainExactly listOf("Test", "Next line")
        buffer.size() shouldBe 0
    }

    test("should handle empty lines") {
        val buffer = LineBuffer(1024)
        val input = "\r\n\r\ntest\r\n".toByteArray()

        val lines = buffer.append(input, input.size)

        lines shouldContainExactly listOf("", "", "test")
        buffer.size() shouldBe 0
    }

    test("should throw exception when line exceeds max size") {
        val buffer = LineBuffer(10)
        val input = "This is a very long line without CRLF".toByteArray()

        shouldThrow<LineTooLongException> {
            buffer.append(input, input.size)
        }
    }

    test("should reset buffer") {
        val buffer = LineBuffer(1024)
        val input = "Partial".toByteArray()

        buffer.append(input, input.size)
        buffer.size() shouldBe 7

        buffer.reset()
        buffer.size() shouldBe 0
    }

    test("should handle mixed complete and partial lines") {
        val buffer = LineBuffer(1024)
        val input = "Complete\r\nPartial".toByteArray()

        val lines = buffer.append(input, input.size)

        lines shouldContainExactly listOf("Complete")
        buffer.size() shouldBe 7  // "Partial"
    }

    test("should handle UTF-8 characters") {
        val buffer = LineBuffer(1024)
        val input = "Hello 世界\r\n".toByteArray(Charsets.UTF_8)

        val lines = buffer.append(input, input.size)

        lines shouldContainExactly listOf("Hello 世界")
        buffer.size() shouldBe 0
    }
})
