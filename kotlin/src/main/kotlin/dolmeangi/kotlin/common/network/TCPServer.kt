package dolmeangi.kotlin.common.network

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Main server implementation for the distributed KV store
 *
 * This server:
 * - Accepts incoming TCP connections
 * - Spawns ConnectionHandlers for each client
 * - Enforces connection limits
 * - Supports graceful shutdown
 *
 * @param config Server configuration
 * @param handler Request handler for processing commands
 */
class TCPServer(
    private val config: ServerConfig = ServerConfig(),
    private val handler: RequestHandler
) : Server {

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)

    // Track connection jobs for graceful shutdown
    private val connectionJobs = mutableListOf<Job>()

    override suspend fun start() {
        if (running.getAndSet(true)) {
            logger.warn { "Server already running" }
            return
        }

        try {
            serverSocket = aSocket(selectorManager)
                .tcp()
                .bind(config.host, config.port) {
                    reuseAddress = true
                    reusePort = true
                }

            logger.info { "Server started on ${config.host}:${config.port}" }

            // Start accepting connections
            serverScope.launch {
                acceptConnections()
            }

        } catch (e: Exception) {
            running.set(false)
            logger.error(e) { "Failed to start server" }
            throw e
        }
    }

    /**
     * Accept incoming connections in a loop
     */
    private suspend fun acceptConnections() {
        val socket = serverSocket ?: return

        logger.info { "Accepting connections..." }

        try {
            while (running.get()) {
                try {
                    val clientSocket = socket.accept()

                    // Check connection limit
                    if (activeConnections.get() >= config.maxConnections) {
                        logger.warn { "Connection limit reached, rejecting connection from ${clientSocket.remoteAddress}" }
                        try {
                            clientSocket.close()
                        } catch (e: Exception) {
                            logger.error(e) { "Error closing rejected connection" }
                        }
                        continue
                    }

                    // Spawn connection handler
                    val job = serverScope.launch {
                        activeConnections.incrementAndGet()
                        try {
                            val connectionHandler = ConnectionHandler(clientSocket, config, handler)
                            connectionHandler.handle()
                        } finally {
                            activeConnections.decrementAndGet()
                        }
                    }

                    synchronized(connectionJobs) {
                        connectionJobs.add(job)
                        // Clean up completed jobs
                        connectionJobs.removeIf { it.isCompleted }
                    }

                } catch (e: ClosedReceiveChannelException) {
                    logger.info { "Server socket closed" }
                    break
                } catch (e: Exception) {
                    if (running.get()) {
                        logger.error(e) { "Error accepting connection" }
                    } else {
                        logger.debug { "Accept loop terminated during shutdown" }
                        break
                    }
                }
            }
        } finally {
            logger.info { "Accept loop terminated" }
        }
    }

    override suspend fun stop() {
        if (!running.getAndSet(false)) {
            logger.warn { "Server not running" }
            return
        }

        logger.info { "Stopping server..." }

        try {
            // Close server socket to stop accepting new connections
            serverSocket?.close()
            serverSocket = null

            // Wait for active connections to complete (with timeout)
            val gracePeriodMs = 5000L
            logger.info { "Waiting up to ${gracePeriodMs}ms for ${activeConnections.get()} active connections to complete..." }

            val jobs = synchronized(connectionJobs) {
                connectionJobs.toList()
            }

            withTimeoutOrNull(gracePeriodMs) {
                jobs.forEach { it.join() }
            }

            // Cancel remaining jobs
            val remaining = synchronized(connectionJobs) {
                connectionJobs.filter { it.isActive }
            }

            if (remaining.isNotEmpty()) {
                logger.warn { "Force-closing ${remaining.size} remaining connections" }
                remaining.forEach { it.cancel() }
            }

            // Cancel server scope
            serverScope.cancel()
            selectorManager.close()

            logger.info { "Server stopped" }
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown" }
            throw e
        }
    }

    override suspend fun awaitTermination() {
        serverScope.coroutineContext[Job]?.join()
    }

    override fun isRunning(): Boolean = running.get()

    /**
     * Get current number of active connections
     */
    fun getActiveConnections(): Int = activeConnections.get()
}
