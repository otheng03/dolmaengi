package dolmeangi.kotlin.sequencer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SequencerTest : FunSpec({

    test("starts with default initial sequence 1") {
        val sequencer = Sequencer()
        sequencer.getNext() shouldBe 1
    }

    test("starts with custom initial sequence") {
        val sequencer = Sequencer(initialSequence = 1000)
        sequencer.getNext() shouldBe 1000
    }

    test("returns monotonically increasing sequences") {
        val sequencer = Sequencer(initialSequence = 100)

        sequencer.getNext() shouldBe 100
        sequencer.getNext() shouldBe 101
        sequencer.getNext() shouldBe 102
        sequencer.getNext() shouldBe 103
    }

    test("getCurrent returns current sequence without incrementing") {
        val sequencer = Sequencer(initialSequence = 50)

        sequencer.getCurrent() shouldBe 50
        sequencer.getCurrent() shouldBe 50 // Should not increment

        sequencer.getNext() shouldBe 50
        sequencer.getCurrent() shouldBe 51 // Now it's 51
        sequencer.getCurrent() shouldBe 51 // Still 51
    }

    test("handles large sequence numbers") {
        val sequencer = Sequencer(initialSequence = Long.MAX_VALUE - 5)

        sequencer.getNext() shouldBe Long.MAX_VALUE - 5
        sequencer.getNext() shouldBe Long.MAX_VALUE - 4
        sequencer.getNext() shouldBe Long.MAX_VALUE - 3
    }

    test("handles negative initial sequence") {
        val sequencer = Sequencer(initialSequence = -100)

        sequencer.getNext() shouldBe -100
        sequencer.getNext() shouldBe -99
        sequencer.getNext() shouldBe -98
    }

    test("handles zero initial sequence") {
        val sequencer = Sequencer(initialSequence = 0)

        sequencer.getNext() shouldBe 0
        sequencer.getNext() shouldBe 1
        sequencer.getNext() shouldBe 2
    }
})