package dolmeangi.kotlin.logserver.config

/**
 * Configuration for the Log Server
 */
data class LogServerConfig(
    /**
     * Network interface to bind to (e.g., "0.0.0.0" for all interfaces)
     */
    val host: String = "0.0.0.0",

    /**
     * TCP port for client connections
     */
    val port: Int = 10002,

    /**
     * Maximum number of concurrent connections
     */
    val maxConnections: Int = 1000,

    /**
     * Directory for storing log files
     */
    val dataDir: String = "./data/logserver",

    /**
     * Maximum size of a log segment file in bytes (default: 64MB)
     */
    val maxSegmentSizeBytes: Long = 64 * 1024 * 1024,

    /**
     * Maximum number of entries per segment (default: 100,000)
     */
    val maxEntriesPerSegment: Int = 100_000
)