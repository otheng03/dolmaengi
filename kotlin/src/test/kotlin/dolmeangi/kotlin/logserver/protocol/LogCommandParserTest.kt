package dolmeangi.kotlin.logserver.protocol

import dolmeangi.kotlin.logserver.model.WriteOperation
import dolmeangi.kotlin.logserver.model.WriteType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LogCommandParserTest : StringSpec({

    "should parse APPEND_LOG command" {
        val writesJson = """[{"type":"PUT","key":"key1","value":"value1","version":1}]"""
        val command = LogCommandParser.parse("APPEND_LOG 1001 1234567890 $writesJson")

        command.shouldBeInstanceOf<LogCommand.AppendLog>()
        command.transactionId shouldBe 1001
        command.commitTimestamp shouldBe 1234567890
        command.writes.size shouldBe 1
        command.writes[0].type shouldBe WriteType.PUT
        command.writes[0].key shouldBe "key1"
        command.writes[0].value shouldBe "value1"
        command.writes[0].version shouldBe 1
    }

    "should parse APPEND_LOG with multiple writes" {
        val writes = listOf(
            WriteOperation(WriteType.PUT, "key1", "value1", 1),
            WriteOperation(WriteType.DELETE, "key2", null, 2)
        )
        val writesJson = LogCommandParser.encodeWrites(writes)
        val command = LogCommandParser.parse("APPEND_LOG 1001 1234567890 $writesJson")

        command.shouldBeInstanceOf<LogCommand.AppendLog>()
        command.writes.size shouldBe 2
        command.writes[0].type shouldBe WriteType.PUT
        command.writes[1].type shouldBe WriteType.DELETE
        command.writes[1].value shouldBe null
    }

    "should fail APPEND_LOG with missing arguments" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("APPEND_LOG 1001")
        }
    }

    "should fail APPEND_LOG with invalid transaction ID" {
        val writesJson = """[{"type":"PUT","key":"k","value":"v","version":1}]"""
        shouldThrow<LogParseException> {
            LogCommandParser.parse("APPEND_LOG invalid 1234567890 $writesJson")
        }
    }

    "should fail APPEND_LOG with invalid commit timestamp" {
        val writesJson = """[{"type":"PUT","key":"k","value":"v","version":1}]"""
        shouldThrow<LogParseException> {
            LogCommandParser.parse("APPEND_LOG 1001 invalid $writesJson")
        }
    }

    "should fail APPEND_LOG with invalid JSON" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("APPEND_LOG 1001 1234567890 {invalid-json}")
        }
    }

    "should fail APPEND_LOG with empty writes" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("APPEND_LOG 1001 1234567890 []")
        }
    }

    "should parse GET_LOGS with start and end index" {
        val command = LogCommandParser.parse("GET_LOGS 1 10")

        command.shouldBeInstanceOf<LogCommand.GetLogs>()
        command.startIndex shouldBe 1
        command.endIndex shouldBe 10
    }

    "should parse GET_LOGS with only start index" {
        val command = LogCommandParser.parse("GET_LOGS 5")

        command.shouldBeInstanceOf<LogCommand.GetLogs>()
        command.startIndex shouldBe 5
        command.endIndex shouldBe null
    }

    "should fail GET_LOGS with invalid start index" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("GET_LOGS invalid")
        }
    }

    "should fail GET_LOGS with invalid end index" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("GET_LOGS 1 invalid")
        }
    }

    "should fail GET_LOGS with end < start" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("GET_LOGS 10 5")
        }
    }

    "should parse GET_LAST_INDEX command" {
        val command = LogCommandParser.parse("GET_LAST_INDEX")

        command.shouldBeInstanceOf<LogCommand.GetLastIndex>()
    }

    "should fail GET_LAST_INDEX with extra arguments" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("GET_LAST_INDEX extra")
        }
    }

    "should fail with unknown command" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("UNKNOWN_COMMAND")
        }
    }

    "should fail with empty command" {
        shouldThrow<LogParseException> {
            LogCommandParser.parse("")
        }
    }

    "should handle case-insensitive commands" {
        val writesJson = """[{"type":"PUT","key":"k","value":"v","version":1}]"""
        val command1 = LogCommandParser.parse("append_log 1001 1234567890 $writesJson")
        val command2 = LogCommandParser.parse("APPEND_LOG 1001 1234567890 $writesJson")

        command1.shouldBeInstanceOf<LogCommand.AppendLog>()
        command2.shouldBeInstanceOf<LogCommand.AppendLog>()
    }
})