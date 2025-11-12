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
    val maxEntriesPerSegment: Int = 100_000,

    // ===== Raft Configuration (optional - for clustered mode) =====

    /**
     * Enable Raft consensus protocol
     * If false, server runs in single-node mode
     */
    val raftEnabled: Boolean = false,

    /**
     * Cluster specification string
     * Format: "nodeId@host:clientPort:raftPort,..."
     * Example: "1@localhost:10002:10102,2@localhost:10003:10103,3@localhost:10004:10104"
     * Required if raftEnabled is true
     */
    val clusterSpec: String? = null,

    /**
     * Minimum election timeout in milliseconds (default: 1500ms)
     */
    val electionTimeoutMinMs: Long = 1500,

    /**
     * Maximum election timeout in milliseconds (default: 3000ms)
     */
    val electionTimeoutMaxMs: Long = 3000,

    /**
     * Heartbeat interval in milliseconds (default: 500ms)
     */
    val heartbeatIntervalMs: Long = 500
) {
    init {
        if (raftEnabled) {
            require(clusterSpec != null) { "clusterSpec is required when Raft is enabled" }
        }
    }
}