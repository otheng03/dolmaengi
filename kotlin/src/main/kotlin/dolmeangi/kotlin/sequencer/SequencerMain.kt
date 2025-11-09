package dolmeangi.kotlin.sequencer

import dolmeangi.kotlin.common.network.TCPServer
import dolmeangi.kotlin.common.network.ServerConfig
import dolmeangi.kotlin.sequencer.config.SequencerConfig
import dolmeangi.kotlin.sequencer.handler.SequencerHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for the standalone Sequencer server
 *
 * This server provides globally unique sequence numbers for distributed systems.
 *
 * Usage:
 *   ./gradlew :sequencer --args="--initial=1000 --port=10001"
 *
 * Command-line arguments:
 *   --initial=<number>  Initial sequence number (default: 1)
 *   --port=<number>     TCP port to listen on (default: 10001)
 *   --host=<address>    Network interface to bind (default: 0.0.0.0)
 *
 * Test with:
 *   telnet localhost 10001
 *   > GETSEQ
 *   :1000
 */
fun main(args: Array<String>) = runBlocking {
    logger.info { "Starting Sequencer Server..." }

    // Parse command-line arguments
    val config = parseArgs(args)

    logger.info { "Configuration: initialSequence=${config.initialSequence}, host=${config.host}, port=${config.port}" }

    // Create sequencer
    val sequencer = Sequencer(config.initialSequence)

    // Create server with sequencer handler
    val serverConfig = ServerConfig(
        host = config.host,
        port = config.port,
        maxConnections = config.maxConnections
    )

    val server = TCPServer(
        config = serverConfig,
        handler = SequencerHandler(sequencer)
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

    logger.info { "Sequencer server running on ${config.host}:${config.port}" }
    logger.info { "Initial sequence: ${config.initialSequence}" }
    logger.info { "Press Ctrl+C to stop" }
    logger.info { "Test with: telnet localhost ${config.port}" }

    // Wait for termination
    server.awaitTermination()

    logger.info { "Sequencer server terminated" }
}

/**
 * Parse command-line arguments into SequencerConfig
 *
 * Supported formats:
 *   --initial=1000
 *   --port=10001
 *   --host=0.0.0.0
 */
private fun parseArgs(args: Array<String>): SequencerConfig {
    var initialSequence = 1L
    var host = "0.0.0.0"
    var port = 10001
    var maxConnections = 1000

    args.forEach { arg ->
        when {
            arg.startsWith("--initial=") -> {
                initialSequence = arg.substringAfter("=").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid initial sequence: $arg")
            }
            arg.startsWith("--port=") -> {
                port = arg.substringAfter("=").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port: $arg")
            }
            arg.startsWith("--host=") -> {
                host = arg.substringAfter("=")
            }
            arg.startsWith("--max-connections=") -> {
                maxConnections = arg.substringAfter("=").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid max-connections: $arg")
            }
            else -> {
                logger.warn { "Unknown argument: $arg (ignored)" }
            }
        }
    }

    return SequencerConfig(
        initialSequence = initialSequence,
        host = host,
        port = port,
        maxConnections = maxConnections
    )
}