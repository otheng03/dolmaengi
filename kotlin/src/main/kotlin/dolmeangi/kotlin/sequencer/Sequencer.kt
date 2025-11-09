package dolmeangi.kotlin.sequencer

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Sequencer provides globally unique, monotonically increasing sequence numbers
 *
 * This is a core component for distributed systems that need:
 * - Unique transaction IDs
 * - Ordered event numbering
 * - Distributed coordination
 *
 * Thread-safe implementation using AtomicLong for lock-free operations.
 *
 * @param initialSequence The starting sequence number (inclusive)
 */
class Sequencer(initialSequence: Long = 1) {

    private val sequence = AtomicLong(initialSequence)

    init {
        logger.info { "Sequencer initialized with starting sequence: $initialSequence" }
    }

    /**
     * Get the next sequence number
     *
     * This operation is atomic and thread-safe.
     * Each call returns a unique, monotonically increasing number.
     *
     * @return The next sequence number
     */
    fun getNext(): Long {
        val next = sequence.getAndIncrement()
        logger.debug { "Issued sequence: $next" }
        return next
    }

    /**
     * Get the current sequence number without incrementing
     *
     * Note: This value may become stale immediately in concurrent scenarios.
     * Use only for monitoring/debugging purposes.
     *
     * @return The current sequence number
     */
    fun getCurrent(): Long {
        return sequence.get()
    }
}