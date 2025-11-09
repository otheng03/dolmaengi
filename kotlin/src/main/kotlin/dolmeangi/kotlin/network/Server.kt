package dolmeangi.kotlin.network

/**
 * Configuration for the DKV server
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 10000,  // Redis-like default
    val maxConnections: Int = 1000,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val idleTimeoutMs: Long = 300_000,  // 5 minutes
    val maxLineSize: Int = 64 * 1024,   // 64KB
    val socketBufferSize: Int = 8192,
    val backlogSize: Int = 128
)

/**
 * Server interface for lifecycle management
 */
interface Server {
    /**
     * Start the server and begin accepting connections
     */
    suspend fun start()

    /**
     * Stop accepting new connections and gracefully shutdown
     */
    suspend fun stop()

    /**
     * Await server termination
     */
    suspend fun awaitTermination()

    /**
     * Check if server is currently running
     */
    fun isRunning(): Boolean
}
