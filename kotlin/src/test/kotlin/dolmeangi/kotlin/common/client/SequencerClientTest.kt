package dolmeangi.kotlin.common.client

import dolmeangi.kotlin.common.network.RequestHandler
import dolmeangi.kotlin.common.network.ServerConfig
import dolmeangi.kotlin.common.network.TCPServer
import dolmeangi.kotlin.sequencer.Sequencer
import dolmeangi.kotlin.sequencer.handler.SequencerHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket

class SequencerClientTest : FunSpec({

    test("client fetches monotonically increasing ids") {
        val port = freePort()
        val server = sequencerServer(port, initial = 500)
        server.start()

        val client = SequencerClient(
            SequencerClientConfig(host = "localhost", port = port)
        )

        try {
            client.getNext() shouldBe 500L
            client.getNext() shouldBe 501L
            client.getCurrent() shouldBe 502L
        } finally {
            client.close()
            server.stop()
        }
    }

    test("client surfaces server-side errors") {
        val port = freePort()
        val server = TCPServer(
            config = ServerConfig(port = port),
            handler = object : RequestHandler {
                override suspend fun handle(request: String): String = "-ERR BROKEN\r\n"
            }
        )

        server.start()

        val client = SequencerClient(
            SequencerClientConfig(host = "localhost", port = port)
        )

        try {
            shouldThrow<SequencerClientException> {
                client.getNext()
            }.message shouldBe "Server error ERR: BROKEN"
        } finally {
            client.close()
            server.stop()
        }
    }
})

private fun sequencerServer(port: Int, initial: Long): TCPServer {
    val handler = SequencerHandler(Sequencer(initial))
    return TCPServer(
        config = ServerConfig(port = port),
        handler = handler
    )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
