package dolmeangi.kotlin.kvstore

import dolmeangi.kotlin.common.client.SequencerClient
import dolmeangi.kotlin.common.client.SequencerClientConfig
import dolmeangi.kotlin.kvstore.handler.KVSPPHandler
import dolmeangi.kotlin.kvstore.transaction.TransactionManager
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

    // Initialize SequencerClient
    val sequencerConfig = SequencerClientConfig(
        host = "localhost",
        port = 10001,
        connectTimeoutMs = 5_000,
        requestTimeoutMs = 5_000
    )
    
    val sequencerClient = SequencerClient(sequencerConfig)
    logger.info { "SequencerClient initialized with config: $sequencerConfig" }

    // Test connection to sequencer during startup
    try {
        val currentSeq = sequencerClient.getCurrent()
        logger.info { "Connected to Sequencer successfully. Current sequence: $currentSeq" }
    } catch (e: Exception) {
        logger.error(e) { "Failed to connect to Sequencer during startup" }
        sequencerClient.close()
        return@runBlocking
    }

    // Initialize TransactionManager with SequencerClient
    val txnManager = TransactionManager(sequencerClient)

    val serverConfig = ServerConfig(
        host = "0.0.0.0",
        port = 10000,
        maxConnections = 1000
    )

    val server = TCPServer(
        config = serverConfig,
        handler = KVSPPHandler(txnManager)
    )

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received" }
        runBlocking {
            server.stop()
            sequencerClient.close()
            logger.info { "SequencerClient closed" }
        }
    })

    // Start server
    server.start()

    logger.info { "Server running on ${serverConfig.host}:${serverConfig.port}" }
    logger.info { "Connected to Sequencer on ${sequencerConfig.host}:${sequencerConfig.port}" }
    logger.info { "Press Ctrl+C to stop" }
    logger.info { "Test with: telnet localhost ${serverConfig.port}" }

    // Wait for termination
    server.awaitTermination()

    logger.info { "Server terminated" }
    sequencerClient.close()
}
