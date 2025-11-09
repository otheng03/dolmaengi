package dolmeangi.kotlin.sequencer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class SequencerConcurrencyTest : FunSpec({

    test("concurrent getNext calls produce unique sequences") {
        val sequencer = Sequencer(initialSequence = 1)
        val numCoroutines = 100
        val sequencesPerCoroutine = 100

        val allSequences = ConcurrentHashMap.newKeySet<Long>()

        coroutineScope {
            val jobs = (1..numCoroutines).map {
                async {
                    repeat(sequencesPerCoroutine) {
                        val seq = sequencer.getNext()
                        allSequences.add(seq)
                    }
                }
            }
            jobs.awaitAll()
        }

        // Should have exactly numCoroutines * sequencesPerCoroutine unique sequences
        allSequences shouldHaveSize (numCoroutines * sequencesPerCoroutine)

        // All sequences should be in expected range
        val expectedSequences = (1L..(numCoroutines * sequencesPerCoroutine).toLong()).toSet()
        allSequences shouldBe expectedSequences
    }

    test("concurrent GETSEQ commands through handler produce unique sequences") {
        val sequencer = Sequencer(initialSequence = 1000)
        val handler = SequencerHandler(sequencer)
        val numCoroutines = 50
        val requestsPerCoroutine = 50

        val allSequences = ConcurrentHashMap.newKeySet<Long>()

        coroutineScope {
            val jobs = (1..numCoroutines).map {
                async {
                    repeat(requestsPerCoroutine) {
                        val response = handler.handle("GETSEQ")
                        // Parse ":1234\r\n" -> 1234
                        val seq = response.trim().substring(1).toLong()
                        allSequences.add(seq)
                    }
                }
            }
            jobs.awaitAll()
        }

        // Should have exactly numCoroutines * requestsPerCoroutine unique sequences
        allSequences shouldHaveSize (numCoroutines * requestsPerCoroutine)

        // All sequences should be in expected range
        val expectedStart = 1000L
        val expectedEnd = expectedStart + (numCoroutines * requestsPerCoroutine) - 1
        val expectedSequences = (expectedStart..expectedEnd).toSet()
        allSequences shouldBe expectedSequences
    }

    test("high contention stress test") {
        val sequencer = Sequencer(initialSequence = 1)
        val numCoroutines = 1000
        val sequencesPerCoroutine = 10

        val allSequences = ConcurrentHashMap.newKeySet<Long>()

        coroutineScope {
            val jobs = (1..numCoroutines).map {
                async {
                    repeat(sequencesPerCoroutine) {
                        val seq = sequencer.getNext()
                        allSequences.add(seq)
                    }
                }
            }
            jobs.awaitAll()
        }

        // Verify no duplicates and all sequences present
        allSequences shouldHaveSize (numCoroutines * sequencesPerCoroutine)
    }

    test("mixed GETSEQ and CURRENT calls are thread-safe") {
        val sequencer = Sequencer(initialSequence = 1)
        val handler = SequencerHandler(sequencer)
        val numCoroutines = 100

        val sequences = ConcurrentHashMap.newKeySet<Long>()

        coroutineScope {
            val jobs = (1..numCoroutines).map { coroutineId ->
                async {
                    repeat(10) {
                        if (coroutineId % 2 == 0) {
                            val response = handler.handle("GETSEQ")
                            val seq = response.trim().substring(1).toLong()
                            sequences.add(seq)
                        } else {
                            // CURRENT doesn't affect uniqueness
                            handler.handle("CURRENT")
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        // Should have 50 coroutines * 10 sequences each = 500 unique sequences
        sequences shouldHaveSize 500
    }

    test("no sequence number is skipped under concurrent load") {
        val sequencer = Sequencer(initialSequence = 1)
        val totalSequences = 1000

        val sequences = ConcurrentHashMap.newKeySet<Long>()

        coroutineScope {
            val jobs = (1..totalSequences).map {
                async {
                    val seq = sequencer.getNext()
                    sequences.add(seq)
                }
            }
            jobs.awaitAll()
        }

        // Verify we got all sequences from 1 to totalSequences
        val expectedSequences = (1L..totalSequences.toLong()).toSet()
        sequences shouldBe expectedSequences
    }
})