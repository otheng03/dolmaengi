package dolmeangi.kotlin.kvstore

import dolmeangi.kotlin.kvstore.handler.KVSPPHandler
import dolmeangi.kotlin.common.network.TCPServer
import dolmeangi.kotlin.common.network.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for the DKV server
 *
 * This starts a server with DKSP protocol support.
 * Connect with: telnet localhost 10000
 */
fun main() = runBlocking {
    logger.info { "Starting Distributed KV Store Server..." }

    val config = ServerConfig(
        host = "0.0.0.0",
        port = 10000,
        maxConnections = 1000
    )

    val server = TCPServer(
        config = config,
        handler = KVSPPHandler()
    )

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received" }
        runBlocking {
            server.stop()
        }
    })

    // Start server
    server.start()

    logger.info { "Server running on ${config.host}:${config.port}" }
    logger.info { "Press Ctrl+C to stop" }
    logger.info { "Test with: telnet localhost ${config.port}" }

    // Wait for termination
    server.awaitTermination()

    logger.info { "Server terminated" }
}
