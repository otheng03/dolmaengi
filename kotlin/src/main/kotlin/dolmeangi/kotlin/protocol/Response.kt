package dolmeangi.kotlin.protocol

/**
 * Represents a DKSP response to send to the client
 */
sealed interface Response {
    /**
     * Status response: +OK\r\n
     */
    data class Status(val message: String) : Response

    /**
     * Error response: -ERROR_TYPE message\r\n
     */
    data class Error(val type: ErrorType, val message: String) : Response

    /**
     * Integer response: :1234567890\r\n
     */
    data class Integer(val value: Long) : Response

    /**
     * String response: text\r\n
     */
    data class StringValue(val value: String) : Response

    /**
     * Null response: $-1\r\n
     */
    data object Null : Response

    companion object {
        /** Standard OK response */
        val OK = Status("OK")
    }
}

/**
 * Standard error types for DKSP
 */
enum class ErrorType {
    ERR,         // Generic error
    CONFLICT,    // Transaction conflict
    NOTFOUND,    // Transaction/resource not found
    INVALID,     // Invalid argument
    ABORTED,     // Transaction already aborted
    STORAGE;     // Storage layer error

    override fun toString(): String = name
}