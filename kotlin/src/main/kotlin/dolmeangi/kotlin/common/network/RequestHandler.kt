package dolmeangi.kotlin.common.network

/**
 * Interface for handling client requests
 *
 * This interface decouples the network layer from protocol parsing
 * and business logic, allowing pluggable implementations.
 */
interface RequestHandler {
    /**
     * Process a request line and return a response
     *
     * @param request The request line (without CRLF)
     * @return The response (with CRLF)
     */
    suspend fun handle(request: String): String
}
