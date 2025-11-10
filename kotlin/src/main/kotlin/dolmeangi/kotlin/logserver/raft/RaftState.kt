package dolmeangi.kotlin.logserver.raft

/**
 * Raft node states
 *
 * A Raft node is always in one of these three states:
 * - FOLLOWER: Normal state, receives AppendEntries from leader
 * - CANDIDATE: Actively requesting votes for leader election
 * - LEADER: Accepts client requests and replicates log to followers
 */
enum class RaftState {
    /**
     * Follower state
     *
     * - Responds to RPCs from candidates and leaders
     * - If election timeout elapses without hearing from leader, converts to candidate
     */
    FOLLOWER,

    /**
     * Candidate state
     *
     * - Increments term, votes for self
     * - Sends RequestVote RPCs to all peers
     * - If receives votes from majority: becomes leader
     * - If receives AppendEntries from new leader: becomes follower
     * - If election timeout elapses: starts new election
     */
    CANDIDATE,

    /**
     * Leader state
     *
     * - Sends periodic heartbeats (empty AppendEntries) to all followers
     * - Accepts client requests and appends to local log
     * - Replicates log entries to followers
     * - If discovers higher term: becomes follower
     */
    LEADER
}
