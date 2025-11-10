package dolmeangi.kotlin.common.client

import dolmeangi.kotlin.common.network.EchoHandler
import dolmeangi.kotlin.common.network.ServerConfig
import dolmeangi.kotlin.common.network.TCPServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket

class TCPClientTest : FunSpec({

    test("client sends commands and receives responses") {
        val port = freePort()
        val server = TCPServer(
            config = ServerConfig(port = port),
            handler = EchoHandler()
        )

        server.start()

        val client = TCPClient(
            TCPClientConfig(host = "localhost", port = port)
        )

        try {
            runBlocking {
                client.send("PING") shouldBe "+ECHO: PING"
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    test("client reconnects after disconnect") {
        val port = freePort()
        val server = TCPServer(
            config = ServerConfig(port = port),
            handler = EchoHandler()
        )

        server.start()

        val client = TCPClient(
            TCPClientConfig(host = "localhost", port = port)
        )

        try {
            runBlocking {
                client.send("First") shouldBe "+ECHO: First"
                client.disconnect()
                client.send("Second") shouldBe "+ECHO: Second"
            }
        } finally {
            client.close()
            server.stop()
        }
    }
})

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
