package dolmeangi.kotlin.common.protocol

/**
 * Encoder for DKSP responses
 *
 * Converts Response objects into DKSP wire format with CRLF line terminators.
 */
object ResponseEncoder {

    private const val CRLF = "\r\n"

    /**
     * Encode a Response into DKSP format
     *
     * @param response The response to encode
     * @return DKSP-formatted string with CRLF
     */
    fun encode(response: Response): String = when (response) {
        is Response.Status -> encodeStatus(response.message)
        is Response.Error -> encodeError(response.type, response.message)
        is Response.Integer -> encodeInteger(response.value)
        is Response.StringValue -> encodeString(response.value)
        is Response.Null -> encodeNull()
    }

    /**
     * Encode a status response: +OK\r\n
     */
    private fun encodeStatus(message: String): String {
        validateNoNewlines(message, "Status message")
        return "+$message$CRLF"
    }

    /**
     * Encode an error response: -ERROR_TYPE message\r\n
     */
    private fun encodeError(type: ErrorType, message: String): String {
        validateNoNewlines(message, "Error message")
        return "-$type $message$CRLF"
    }

    /**
     * Encode an integer response: :1234567890\r\n
     */
    private fun encodeInteger(value: Long): String {
        return ":$value$CRLF"
    }

    /**
     * Encode a string response: text\r\n
     */
    private fun encodeString(value: String): String {
        validateNoNewlines(value, "String value")
        return "$value$CRLF"
    }

    /**
     * Encode a null response: $-1\r\n
     */
    private fun encodeNull(): String {
        return "$-1$CRLF"
    }

    /**
     * Validate that a string doesn't contain CR or LF characters
     */
    private fun validateNoNewlines(value: String, fieldName: String) {
        if (value.contains('\r') || value.contains('\n')) {
            throw IllegalArgumentException("$fieldName cannot contain CR or LF characters")
        }
    }
}