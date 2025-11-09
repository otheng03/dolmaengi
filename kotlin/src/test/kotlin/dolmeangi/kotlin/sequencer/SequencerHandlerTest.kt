package dolmeangi.kotlin.sequencer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class SequencerHandlerTest : FunSpec({

    test("GETSEQ returns next sequence number") {
        val sequencer = Sequencer(initialSequence = 100)
        val handler = SequencerHandler(sequencer)

        val response1 = handler.handle("GETSEQ")
        response1 shouldBe ":100\r\n"

        val response2 = handler.handle("GETSEQ")
        response2 shouldBe ":101\r\n"

        val response3 = handler.handle("GETSEQ")
        response3 shouldBe ":102\r\n"
    }

    test("GETSEQ is case insensitive") {
        val sequencer = Sequencer(initialSequence = 1)
        val handler = SequencerHandler(sequencer)

        handler.handle("getseq") shouldBe ":1\r\n"
        handler.handle("GETSEQ") shouldBe ":2\r\n"
        handler.handle("GetSeq") shouldBe ":3\r\n"
        handler.handle("gEtSeQ") shouldBe ":4\r\n"
    }

    test("CURRENT returns current sequence without incrementing") {
        val sequencer = Sequencer(initialSequence = 50)
        val handler = SequencerHandler(sequencer)

        handler.handle("CURRENT") shouldBe ":50\r\n"
        handler.handle("CURRENT") shouldBe ":50\r\n"

        handler.handle("GETSEQ") shouldBe ":50\r\n"
        handler.handle("CURRENT") shouldBe ":51\r\n"
        handler.handle("CURRENT") shouldBe ":51\r\n"
    }

    test("CURRENT is case insensitive") {
        val sequencer = Sequencer(initialSequence = 10)
        val handler = SequencerHandler(sequencer)

        handler.handle("current") shouldBe ":10\r\n"
        handler.handle("CURRENT") shouldBe ":10\r\n"
        handler.handle("Current") shouldBe ":10\r\n"
    }

    test("handles whitespace in commands") {
        val sequencer = Sequencer(initialSequence = 1)
        val handler = SequencerHandler(sequencer)

        handler.handle("  GETSEQ  ") shouldBe ":1\r\n"
        handler.handle("\tCURRENT\t") shouldBe ":2\r\n"
    }

    test("unknown command returns error") {
        val sequencer = Sequencer(initialSequence = 1)
        val handler = SequencerHandler(sequencer)

        val response = handler.handle("FOOBAR")
        response.shouldStartWith("-ERR")
        response shouldContain "Unknown command"
        response shouldContain "FOOBAR"
    }

    test("empty command returns error") {
        val sequencer = Sequencer(initialSequence = 1)
        val handler = SequencerHandler(sequencer)

        val response = handler.handle("")
        response.shouldStartWith("-ERR")
    }

    test("handles multiple requests in sequence") {
        val sequencer = Sequencer(initialSequence = 1000)
        val handler = SequencerHandler(sequencer)

        handler.handle("GETSEQ") shouldBe ":1000\r\n"
        handler.handle("CURRENT") shouldBe ":1001\r\n"
        handler.handle("GETSEQ") shouldBe ":1001\r\n"
        handler.handle("GETSEQ") shouldBe ":1002\r\n"
        handler.handle("CURRENT") shouldBe ":1003\r\n"
        handler.handle("GETSEQ") shouldBe ":1003\r\n"
    }

    test("handles negative sequence numbers") {
        val sequencer = Sequencer(initialSequence = -5)
        val handler = SequencerHandler(sequencer)

        handler.handle("GETSEQ") shouldBe ":-5\r\n"
        handler.handle("GETSEQ") shouldBe ":-4\r\n"
        handler.handle("CURRENT") shouldBe ":-3\r\n"
    }

    test("handles very large sequence numbers") {
        val sequencer = Sequencer(initialSequence = Long.MAX_VALUE - 2)
        val handler = SequencerHandler(sequencer)

        handler.handle("GETSEQ") shouldBe ":${Long.MAX_VALUE - 2}\r\n"
        handler.handle("GETSEQ") shouldBe ":${Long.MAX_VALUE - 1}\r\n"
    }
})