package dolmeangi.kotlin.common.transaction

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

@OptIn(ExperimentalAtomicApi::class)
class MockSequencerClient : SequenceGenerator {
    private val sequence = AtomicLong(1L)
    override suspend fun getNext(): Long = sequence.fetchAndIncrement()
    override suspend fun getCurrent(): Long = sequence.fetchAndAdd(0)
}
