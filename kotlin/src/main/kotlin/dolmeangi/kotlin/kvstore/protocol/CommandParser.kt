package dolmeangi.kotlin.kvstore.protocol

/**
 * Exception thrown when command parsing fails
 */
class ParseException(message: String) : Exception(message)

/**
 * Parser for DKSP commands
 *
 * Parses space-separated command strings into Command objects.
 * Format: COMMAND [arg1] [arg2] ...
 *
 * Transaction IDs are prefixed with ':' (e.g., :1001)
 */
object CommandParser {

    /**
     * Parse a command line into a Command object
     *
     * @param line The command line (without CRLF)
     * @return Parsed Command
     * @throws ParseException if parsing fails
     */
    fun parse(line: String): Command {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            throw ParseException("Empty command")
        }

        // Split into tokens (command and arguments)
        val tokens = trimmed.split(" ", limit = 4)
        val command = tokens[0].uppercase()

        return when (command) {
            "BEGIN" -> {
                validateArgCount(tokens, 1, command)
                Command.Begin
            }

            "COMMIT" -> {
                validateArgCount(tokens, 2, command)
                val txnId = parseTransactionId(tokens[1], command)
                Command.Commit(txnId)
            }

            "ABORT" -> {
                validateArgCount(tokens, 2, command)
                val txnId = parseTransactionId(tokens[1], command)
                Command.Abort(txnId)
            }

            "GET" -> {
                validateArgCount(tokens, 3, command)
                val txnId = parseTransactionId(tokens[1], command)
                val key = tokens[2]
                validateKey(key)
                Command.Get(txnId, key)
            }

            "PUT" -> {
                validateMinArgCount(tokens, 4, command)
                val txnId = parseTransactionId(tokens[1], command)
                val key = tokens[2]
                validateKey(key)
                val value = tokens[3]
                Command.Put(txnId, key, value)
            }

            "DELETE" -> {
                validateArgCount(tokens, 3, command)
                val txnId = parseTransactionId(tokens[1], command)
                val key = tokens[2]
                validateKey(key)
                Command.Delete(txnId, key)
            }

            else -> throw ParseException("Unknown command '$command'")
        }
    }

    /**
     * Parse a transaction ID from a string (format: :1234567890)
     */
    private fun parseTransactionId(token: String, command: String): TransactionId {
        if (!token.startsWith(":")) {
            throw ParseException("$command: Transaction ID must start with ':' (got '$token')")
        }

        val idString = token.substring(1)
        val id = idString.toLongOrNull()
            ?: throw ParseException("$command: Invalid transaction ID '$token'")

        return TransactionId(id)
    }

    /**
     * Validate that we have exactly the expected number of tokens
     */
    private fun validateArgCount(tokens: List<String>, expected: Int, command: String) {
        if (tokens.size != expected) {
            throw ParseException("$command: Expected $expected arguments, got ${tokens.size}")
        }
    }

    /**
     * Validate that we have at least the expected number of tokens
     */
    private fun validateMinArgCount(tokens: List<String>, minimum: Int, command: String) {
        if (tokens.size < minimum) {
            throw ParseException("$command: Expected at least $minimum arguments, got ${tokens.size}")
        }
    }

    /**
     * Validate a key (non-empty)
     */
    private fun validateKey(key: String) {
        if (key.isEmpty()) {
            throw ParseException("Empty key not allowed")
        }
    }
}