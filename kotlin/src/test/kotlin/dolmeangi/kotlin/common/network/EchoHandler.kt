package dolmeangi.kotlin.common.network

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Simple echo handler for testing the network layer
 *
 * Responds with "+ECHO: <request>\r\n" for any request
 */
class EchoHandler : RequestHandler {
    override suspend fun handle(request: String): String {
        logger.debug { "Handling request: $request" }
        return "+ECHO: $request\r\n"
    }
}
