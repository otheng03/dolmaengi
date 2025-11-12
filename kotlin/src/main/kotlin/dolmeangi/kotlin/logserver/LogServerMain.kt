package dolmeangi.kotlin.logserver

import dolmeangi.kotlin.common.network.ServerConfig
import dolmeangi.kotlin.common.network.TCPServer
import dolmeangi.kotlin.logserver.config.LogServerConfig
import dolmeangi.kotlin.logserver.handler.LogServerHandler
import dolmeangi.kotlin.logserver.storage.LogStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * Main entry point for the standalone Log Server
 *
 * This server provides durable write-ahead logging with Raft consensus (Phase 2).
 * For Phase 1, it operates in single-node mode.
 *
 * Usage:
 *   ./gradlew :logserver --args="--port=10002 --data-dir=./data/logserver"
 *
 * Command-line arguments:
 *   --port=<number>           TCP port to listen on (default: 10002)
 *   --host=<address>          Network interface to bind (default: 0.0.0.0)
 *   --data-dir=<path>         Directory for log storage (default: ./data/logserver)
 *   --max-connections=<num>   Maximum concurrent connections (default: 1000)
 *   --segment-size=<bytes>    Maximum segment file size (default: 67108864 = 64MB)
 *   --max-entries=<num>       Maximum entries per segment (default: 100000)
 *
 * Test with:
 *   telnet localhost 10002
 *   > GET_LAST_INDEX
 */
fun main(args: Array<String>) = runBlocking {
    logger.info { "Starting Log Server..." }

    // Parse command-line arguments
    val config = parseArgs(args)

    logger.info {
        "Configuration: host=${config.host}, port=${config.port}, " +
        "dataDir=${config.dataDir}"
    }

    // Create log storage
    val storage = LogStorage(
        dataDir = Paths.get(config.dataDir),
        maxSegmentSizeBytes = config.maxSegmentSizeBytes,
        maxEntriesPerSegment = config.maxEntriesPerSegment
    )

    logger.info { "Log storage initialized at ${config.dataDir}" }

    // Create server with log handler
    val serverConfig = ServerConfig(
        host = config.host,
        port = config.port,
        maxConnections = config.maxConnections
    )

    val server = TCPServer(
        config = serverConfig,
        handler = LogServerHandler(storage)
    )

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received" }
        runBlocking {
            server.stop()
            storage.close()
            logger.info { "Log storage closed" }
        }
    })

    // Start server
    server.start()

    logger.info { "Log server running on ${config.host}:${config.port}" }
    logger.info { "Data directory: ${config.dataDir}" }
    logger.info { "Press Ctrl+C to stop" }
    logger.info { "Test with: telnet localhost ${config.port}" }

    // Display current log state
    if (!storage.isEmpty()) {
        val firstIndex = storage.getFirstIndex()
        val lastIndex = storage.getLastIndex()
        logger.info { "Existing log: indices $firstIndex to $lastIndex" }
    } else {
        logger.info { "Empty log, starting fresh" }
    }

    // Wait for termination
    server.awaitTermination()

    logger.info { "Log server terminated" }
    storage.close()
}

/**
 * Parse command-line arguments into LogServerConfig
 *
 * Supported formats:
 *   --port=10002
 *   --host=0.0.0.0
 *   --data-dir=./data/logserver
 *   --node-id=1
 *   --cluster=1@localhost:10002:10102,2@localhost:10003:10103,3@localhost:10004:10104
 */
private fun parseArgs(args: Array<String>): LogServerConfig {
    var host = "0.0.0.0"
    var port = 10002
    var maxConnections = 1000
    var dataDir = "./data/logserver"
    var maxSegmentSizeBytes = 64L * 1024 * 1024
    var maxEntriesPerSegment = 100_000

    // Raft parameters
    var clusterSpec: String? = null
    var electionTimeoutMinMs = 1500L
    var electionTimeoutMaxMs = 3000L
    var heartbeatIntervalMs = 500L

    args.forEach { arg ->
        when {
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
            arg.startsWith("--data-dir=") -> {
                dataDir = arg.substringAfter("=")
            }
            arg.startsWith("--segment-size=") -> {
                maxSegmentSizeBytes = arg.substringAfter("=").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid segment-size: $arg")
            }
            arg.startsWith("--max-entries=") -> {
                maxEntriesPerSegment = arg.substringAfter("=").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid max-entries: $arg")
            }
            arg.startsWith("--node-id=") -> {
                val id = arg.substringAfter("=").toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid node-id: $arg")
            }
            arg.startsWith("--cluster=") -> {
                clusterSpec = arg.substringAfter("=")
            }
            arg.startsWith("--election-timeout-min=") -> {
                electionTimeoutMinMs = arg.substringAfter("=").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid election-timeout-min: $arg")
            }
            arg.startsWith("--election-timeout-max=") -> {
                electionTimeoutMaxMs = arg.substringAfter("=").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid election-timeout-max: $arg")
            }
            arg.startsWith("--heartbeat-interval=") -> {
                heartbeatIntervalMs = arg.substringAfter("=").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid heartbeat-interval: $arg")
            }
            else -> {
                logger.warn { "Unknown argument: $arg (ignored)" }
            }
        }
    }

    return LogServerConfig(
        host = host,
        port = port,
        maxConnections = maxConnections,
        dataDir = dataDir,
        maxSegmentSizeBytes = maxSegmentSizeBytes,
        maxEntriesPerSegment = maxEntriesPerSegment,
        clusterSpec = clusterSpec,
        electionTimeoutMinMs = electionTimeoutMinMs,
        electionTimeoutMaxMs = electionTimeoutMaxMs,
        heartbeatIntervalMs = heartbeatIntervalMs
    )
}