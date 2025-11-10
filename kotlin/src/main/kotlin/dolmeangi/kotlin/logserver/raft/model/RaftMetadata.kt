package dolmeangi.kotlin.logserver.raft.model

import kotlinx.serialization.Serializable

/**
 * Raft persistent state
 *
 * This metadata MUST be persisted to disk before responding to RPCs.
 * It ensures correctness across crashes and restarts.
 *
 * From Raft paper Figure 2:
 * - currentTerm: Latest term server has seen (initialized to 0, increases monotonically)
 * - votedFor: CandidateId that received vote in current term (or null if none)
 *
 * @property currentTerm Latest term this server has seen
 * @property votedFor NodeId that received vote in current term, or null
 */
@Serializable
data class RaftMetadata(
    val currentTerm: Long = 0,
    val votedFor: NodeId? = null
) {
    init {
        require(currentTerm >= 0) { "Term must be non-negative, got: $currentTerm" }
    }

    /**
     * Create a new metadata with updated term
     * Clears votedFor when term changes
     */
    fun withTerm(newTerm: Long): RaftMetadata {
        require(newTerm >= currentTerm) { "Cannot decrease term from $currentTerm to $newTerm" }
        return if (newTerm == currentTerm) {
            this
        } else {
            RaftMetadata(currentTerm = newTerm, votedFor = null)
        }
    }

    /**
     * Create a new metadata with updated vote
     * Term must remain the same
     */
    fun withVote(candidateId: NodeId): RaftMetadata {
        return copy(votedFor = candidateId)
    }

    /**
     * Check if we've already voted in this term
     */
    fun hasVoted(): Boolean = votedFor != null

    /**
     * Check if we voted for the given candidate
     */
    fun votedFor(candidateId: NodeId): Boolean = votedFor == candidateId

    override fun toString(): String {
        return "RaftMetadata(term=$currentTerm, votedFor=$votedFor)"
    }
}