package dolmeangi.kotlin.sequencer.config

/**
 * Configuration for the Sequencer server
 *
 * @param initialSequence Starting sequence number (default: 1)
 * @param host Network interface to bind to (default: 0.0.0.0)
 * @param port TCP port to listen on (default: 10001)
 * @param maxConnections Maximum concurrent client connections (default: 1000)
 */
data class SequencerConfig(
    val initialSequence: Long = 1,
    val host: String = "0.0.0.0",
    val port: Int = 10001,
    val maxConnections: Int = 1000
)