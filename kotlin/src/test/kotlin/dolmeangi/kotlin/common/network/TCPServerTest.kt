package dolmeangi.kotlin.common.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

class TCPServerTest : FunSpec({

    test("server should start and stop successfully") {
        val server = TCPServer(
            config = ServerConfig(port = 9001),
            handler = EchoHandler()
        )

        server.isRunning() shouldBe false

        server.start()
        server.isRunning() shouldBe true

        server.stop()
        server.isRunning() shouldBe false
    }

    test("server should accept connections and echo messages") {
        val server = TCPServer(
            config = ServerConfig(port = 9002),
            handler = EchoHandler()
        )

        server.start()

        try {
            // Connect as a client
            val selectorManager = SelectorManager(Dispatchers.IO)
            val socket = aSocket(selectorManager).tcp().connect("localhost", 9002)

            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)

            // Send a message
            output.writeStringUtf8("Hello, Server!\r\n")
            output.flush()

            // Read response
            val response = input.readUTF8Line()

            response shouldBe "+ECHO: Hello, Server!"

            socket.close()
            selectorManager.close()
        } finally {
            server.stop()
        }
    }

    test("server should handle multiple concurrent connections") {
        val server = TCPServer(
            config = ServerConfig(port = 9003, maxConnections = 10),
            handler = EchoHandler()
        )

        server.start()

        try {
            val selectorManager = SelectorManager(Dispatchers.IO)
            val numClients = 5

            // Create multiple concurrent clients
            val jobs = (1..numClients).map { clientId ->
                GlobalScope.async {
                    val socket = aSocket(selectorManager).tcp().connect("localhost", 9003)
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true)

                    val message = "Client $clientId"
                    output.writeStringUtf8("$message\r\n")
                    output.flush()

                    val response = input.readUTF8Line()

                    socket.close()

                    response shouldContain message
                }
            }

            // Wait for all clients to complete
            runBlocking {
                jobs.forEach { it.await() }
            }

            selectorManager.close()
        } finally {
            server.stop()
        }
    }

    test("server should handle multiple messages from same connection") {
        val server = TCPServer(
            config = ServerConfig(port = 9004),
            handler = EchoHandler()
        )

        server.start()

        try {
            val selectorManager = SelectorManager(Dispatchers.IO)
            val socket = aSocket(selectorManager).tcp().connect("localhost", 9004)

            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)

            // Send multiple messages
            val messages = listOf("First", "Second", "Third")

            for (msg in messages) {
                output.writeStringUtf8("$msg\r\n")
                output.flush()

                val response = input.readUTF8Line()
                response shouldBe "+ECHO: $msg"
            }

            socket.close()
            selectorManager.close()
        } finally {
            server.stop()
        }
    }

    test("server should enforce connection limit") {
        val maxConnections = 3
        val server = TCPServer(
            config = ServerConfig(port = 9005, maxConnections = maxConnections),
            handler = EchoHandler()
        )

        server.start()

        try {
            val selectorManager = SelectorManager(Dispatchers.IO)
            val sockets = mutableListOf<Socket>()

            // Connect up to the limit
            repeat(maxConnections) {
                val socket = aSocket(selectorManager).tcp().connect("localhost", 9005)
                sockets.add(socket)
            }

            // Wait a bit for connections to be registered
            delay(100)

            server.getActiveConnections() shouldBe maxConnections

            // Try to connect one more (should be rejected)
            val extraSocket = aSocket(selectorManager).tcp().connect("localhost", 9005)

            // The extra connection should be closed by the server
            delay(100)

            // Clean up
            sockets.forEach { it.close() }
            extraSocket.close()
            selectorManager.close()
        } finally {
            server.stop()
        }
    }

    test("server should handle graceful shutdown") {
        val server = TCPServer(
            config = ServerConfig(port = 9006),
            handler = EchoHandler()
        )

        server.start()

        val selectorManager = SelectorManager(Dispatchers.IO)
        val socket = aSocket(selectorManager).tcp().connect("localhost", 9006)
        val output = socket.openWriteChannel(autoFlush = true)

        // Send a message
        output.writeStringUtf8("Test\r\n")
        output.flush()

        // Stop server (should wait for active connection)
        server.stop()

        server.isRunning() shouldBe false

        socket.close()
        selectorManager.close()
    }
})
