package dolmeangi.kotlin.logserver.raft

import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.RaftMetadata
import dolmeangi.kotlin.logserver.raft.persistence.RaftMetadataStore
import dolmeangi.kotlin.logserver.raft.rpc.RaftRPC
import dolmeangi.kotlin.logserver.raft.rpc.RaftRPCClient
import dolmeangi.kotlin.logserver.storage.LogStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Raft consensus node
 *
 * Implements the Raft consensus protocol for distributed log replication.
 * Manages state transitions, leader election, and log replication.
 *
 * Raft guarantees:
 * - Election Safety: At most one leader per term
 * - Leader Append-Only: Leader never overwrites/deletes entries
 * - Log Matching: If two logs contain same index/term, all preceding entries match
 * - Leader Completeness: If entry committed in term, it's in logs of all future leaders
 * - State Machine Safety: If server applies entry at index, no other server applies different entry at that index
 *
 * @property config Raft cluster configuration
 * @property storage Log storage for replicated entries
 * @property metadataStore Persistent storage for currentTerm and votedFor
 * @property scope Coroutine scope for async operations
 */
class RaftNode(
    private val config: RaftConfig,
    private val storage: LogStorage,
    private val metadataStore: RaftMetadataStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : AutoCloseable {

    // ===== Persistent State (on all servers) =====
    // - currentTerm: Managed by metadataStore
    // - votedFor: Managed by metadataStore
    // - log[]: Managed by storage

    // ===== Volatile State (on all servers) =====
    /**
     * Current Raft state (Follower, Candidate, or Leader)
     */
    private val currentState = AtomicReference(RaftState.FOLLOWER)

    /**
     * Index of highest log entry known to be committed
     * (initialized to 0, increases monotonically)
     */
    @Volatile
    private var commitIndex: Long = 0

    /**
     * Index of highest log entry applied to state machine
     * (initialized to 0, increases monotonically)
     */
    @Volatile
    private var lastApplied: Long = 0

    /**
     * Current leader's NodeId (null if unknown)
     */
    @Volatile
    private var currentLeader: NodeId? = null

    // ===== Volatile State (on leaders) =====
    /**
     * For each server, index of next log entry to send
     * (initialized to leader's last log index + 1)
     */
    private val nextIndex = mutableMapOf<NodeId, Long>()

    /**
     * For each server, index of highest log entry known to be replicated
     * (initialized to 0, increases monotonically)
     */
    private val matchIndex = mutableMapOf<NodeId, Long>()

    // ===== Synchronization =====
    private val stateMutex = Mutex()

    // ===== Timer Jobs =====
    private var electionTimerJob: Job? = null
    private var heartbeatTimerJob: Job? = null

    // ===== RPC Client =====
    private val rpcClient = RaftRPCClient()

    init {
        logger.info { "Initializing Raft node: ${config.nodeId}" }
        logger.info { "Cluster configuration: $config" }
        logger.info { "Initial metadata: ${metadataStore.get()}" }

        // Start as follower (non-suspend initialization)
        currentState.set(RaftState.FOLLOWER)
        currentLeader = null

        logger.info { "Initialized as FOLLOWER in term ${metadataStore.getCurrentTerm()}" }
    }

    // ===== State Queries =====

    /**
     * Get current Raft state
     */
    fun getState(): RaftState = currentState.get()

    /**
     * Check if this node is the leader
     */
    fun isLeader(): Boolean = currentState.get() == RaftState.LEADER

    /**
     * Check if this node is a follower
     */
    fun isFollower(): Boolean = currentState.get() == RaftState.FOLLOWER

    /**
     * Check if this node is a candidate
     */
    fun isCandidate(): Boolean = currentState.get() == RaftState.CANDIDATE

    /**
     * Get current term
     */
    fun getCurrentTerm(): Long = metadataStore.getCurrentTerm()

    /**
     * Get current leader (null if unknown)
     */
    fun getLeader(): NodeId? = currentLeader

    /**
     * Get commit index
     */
    fun getCommitIndex(): Long = commitIndex

    /**
     * Get last applied index
     */
    fun getLastApplied(): Long = lastApplied

    // ===== State Transitions =====

    /**
     * Transition to FOLLOWER state
     *
     * Called when:
     * - Node starts up
     * - Discovers higher term
     * - Candidate loses election
     * - Receives AppendEntries from valid leader
     *
     * @param term New term (must be >= current term)
     * @param leader New leader's NodeId (null if unknown)
     */
    private suspend fun becomeFollower(term: Long, leader: NodeId?) = stateMutex.withLock {
        val oldState = currentState.get()
        val oldTerm = getCurrentTerm()

        logger.info {
            "Becoming FOLLOWER: term=$oldTerm->$term, leader=$leader " +
                    "(was $oldState in term $oldTerm)"
        }

        // Update term if needed
        if (term > oldTerm) {
            metadataStore.updateTerm(term)
        }

        // Update state
        currentState.set(RaftState.FOLLOWER)
        currentLeader = leader

        // Stop leader heartbeats if we were leader
        stopHeartbeatTimer()

        // Start election timer
        resetElectionTimer()
    }

    /**
     * Transition to CANDIDATE state
     *
     * Called when:
     * - Election timeout expires while in FOLLOWER state
     * - Election timeout expires while in CANDIDATE state (new election)
     */
    private suspend fun becomeCandidate() {
        stateMutex.withLock {
            val oldState = currentState.get()
            val oldTerm = getCurrentTerm()
            val newTerm = oldTerm + 1

            logger.info { "Becoming CANDIDATE: term=$oldTerm->$newTerm (was $oldState)" }

            // Increment term and vote for self
            metadataStore.updateTermAndVote(newTerm, config.nodeId)

            // Update state
            currentState.set(RaftState.CANDIDATE)
            currentLeader = null

            // Reset election timer for this election
            resetElectionTimer()
        }

        // Start election outside of mutex to avoid blocking
        startElection()
    }

    // Must be called outside of stateMutex.withLock to avoid blocking other operations
    private suspend fun startElection() {
        val term = getCurrentTerm()
        val (lastLogIndex, lastLogTerm) = storage.getLastLogIndexAndTerm()

        logger.info {
            "Starting election in term $term " +
                    "(lastLogIndex=$lastLogIndex, lastLogTerm=$lastLogTerm)"
        }

        // We've already voted for ourselves in becomeCandidate
        var votesReceived = 1
        val votesNeeded = config.getMajorityQuorum()

        logger.debug { "Need $votesNeeded votes to win election (already have 1 from self)" }

        // Send RequestVote RPCs to all peers in parallel
        val voteJobs = config.getOtherPeers().map { peer ->
            scope.async {
                val request = RaftRPC.RequestVoteRequest(
                    term = term,
                    candidateId = config.nodeId,
                    lastLogIndex = lastLogIndex,
                    lastLogTerm = lastLogTerm
                )

                val response = rpcClient.sendRequestVote(peer, request)
                if (response != null) {
                    Pair(peer.nodeId, response)
                } else {
                    logger.debug { "No response from ${peer.nodeId} for RequestVote" }
                    null
                }
            }
        }

        // Collect votes
        for (job in voteJobs) {
            val result = job.await()
            if (result != null) {
                val (peerId, response) = result

                // Check if we need to step down due to higher term
                if (response.term > term) {
                    logger.info {
                        "Discovered higher term ${response.term} from $peerId, " +
                                "stepping down from candidate"
                    }
                    becomeFollower(response.term, null)
                    return
                }

                // Count vote
                if (response.voteGranted) {
                    votesReceived++
                    logger.debug {
                        "Received vote from $peerId (total: $votesReceived/$votesNeeded)"
                    }

                    // Check if we've won the election
                    if (votesReceived >= votesNeeded) {
                        logger.info { "Won election with $votesReceived votes!" }
                        becomeLeader(term)
                        return
                    }
                } else {
                    logger.debug { "Vote denied by $peerId" }
                }
            }
        }

        logger.info {
            "Election finished with $votesReceived votes " +
                    "(needed $votesNeeded) - did not win"
        }
    }

    /**
     * Transition to LEADER state
     *
     * Called when:
     * - Candidate receives votes from majority of cluster
     *
     * @param term Term in which leadership was won
     */
    private suspend fun becomeLeader(term: Long) = stateMutex.withLock {
        val oldState = currentState.get()

        logger.info { "Becoming LEADER in term $term (was $oldState)" }

        require(term == getCurrentTerm()) {
            "Cannot become leader in term $term, current term is ${getCurrentTerm()}"
        }

        // Update state
        currentState.set(RaftState.LEADER)
        currentLeader = config.nodeId

        // Stop election timer
        stopElectionTimer()

        // Initialize leader volatile state
        initializeLeaderState()

        // Start sending heartbeats
        startHeartbeatTimer()

        logger.info { "Now LEADER in term $term" }
    }

    /**
     * Initialize leader-specific volatile state
     *
     * nextIndex[]: Initialize to last log index + 1
     * matchIndex[]: Initialize to 0
     */
    private fun initializeLeaderState() {
        val lastLogIndex = storage.getLastIndex() ?: 0

        nextIndex.clear()
        matchIndex.clear()

        for (peer in config.getOtherPeers()) {
            nextIndex[peer.nodeId] = lastLogIndex + 1
            matchIndex[peer.nodeId] = 0
        }

        logger.debug {
            "Initialized leader state: nextIndex=$nextIndex, matchIndex=$matchIndex"
        }
    }

    // ===== Election Timer =====

    /**
     * Reset election timer
     *
     * Called when:
     * - Granting vote to candidate
     * - Receiving AppendEntries from valid leader
     * - Starting new election
     */
    private fun resetElectionTimer() {
        stopElectionTimer()

        val timeout = randomElectionTimeout()
        electionTimerJob = scope.launch {
            delay(timeout)
            onElectionTimeout()
        }

        logger.trace { "Reset election timer: ${timeout}ms" }
    }

    /**
     * Stop election timer
     */
    private fun stopElectionTimer() {
        electionTimerJob?.cancel()
        electionTimerJob = null
    }

    /**
     * Generate random election timeout within configured range
     */
    private fun randomElectionTimeout(): Long {
        return (config.electionTimeoutMinMs..config.electionTimeoutMaxMs).random()
    }

    /**
     * Handle election timeout
     *
     * If we're a follower or candidate and haven't heard from a leader,
     * start a new election.
     */
    private suspend fun onElectionTimeout() {
        logger.debug { "Election timeout in state ${getState()}" }

        when (getState()) {
            RaftState.FOLLOWER, RaftState.CANDIDATE -> {
                // Start new election
                becomeCandidate()
            }
            RaftState.LEADER -> {
                // Leaders don't have election timeouts
                logger.warn { "Election timeout fired while LEADER (should not happen)" }
            }
        }
    }

    // ===== Heartbeat Timer =====

    /**
     * Start heartbeat timer (leaders only)
     */
    private fun startHeartbeatTimer() {
        stopHeartbeatTimer()

        heartbeatTimerJob = scope.launch {
            while (isActive) {
                sendHeartbeats()
                delay(config.heartbeatIntervalMs)
            }
        }

        logger.debug { "Started heartbeat timer: interval=${config.heartbeatIntervalMs}ms" }
    }

    /**
     * Stop heartbeat timer
     */
    private fun stopHeartbeatTimer() {
        heartbeatTimerJob?.cancel()
        heartbeatTimerJob = null
    }

    /**
     * Send heartbeats to all followers
     *
     * Heartbeats are empty AppendEntries RPCs.
     * Will be implemented in Milestone 5C.
     */
    private fun sendHeartbeats() {
        if (!isLeader()) {
            logger.warn { "sendHeartbeats called but not leader" }
            return
        }

        logger.trace { "Sending heartbeats to all followers" }
        // TODO: Implement in Milestone 5C
    }

    // ===== RPC Handlers =====

    /**
     * Handle RequestVote RPC from a candidate
     *
     * Implements Raft RequestVote RPC receiver logic (Figure 2 in paper):
     * 1. Reply false if term < currentTerm
     * 2. If votedFor is null or candidateId, and candidate's log is at least
     *    as up-to-date as receiver's log, grant vote
     */
    suspend fun handleRequestVote(request: RaftRPC.RequestVoteRequest): RaftRPC.RequestVoteResponse {
        return stateMutex.withLock {
            val currentTerm = getCurrentTerm()
            val metadata = metadataStore.get()

            logger.debug {
                "HandleRequestVote from ${request.candidateId} " +
                        "(term=${request.term} vs our $currentTerm, " +
                        "lastLog=${request.lastLogIndex}/${request.lastLogTerm})"
            }

            // Rule 1: Reply false if term < currentTerm
            if (request.term < currentTerm) {
                logger.debug {
                    "Rejecting vote for ${request.candidateId}: " +
                            "term too old (${request.term} < $currentTerm)"
                }
                return RaftRPC.RequestVoteResponse(
                    term = currentTerm,
                    voteGranted = false
                )
            }

            // If request term > currentTerm, update our term
            if (request.term > currentTerm) {
                logger.info {
                    "Discovered higher term ${request.term} from ${request.candidateId}, " +
                            "updating term from $currentTerm"
                }
                metadataStore.updateTerm(request.term)

                // If we were leader or candidate, step down
                if (currentState.get() != RaftState.FOLLOWER) {
                    currentState.set(RaftState.FOLLOWER)
                    currentLeader = null
                    stopHeartbeatTimer()
                }
            }

            // Rule 2: Grant vote if:
            // - We haven't voted yet OR we already voted for this candidate
            // - Candidate's log is at least as up-to-date as ours

            val canGrantVote = metadata.votedFor == null || metadata.votedFor == request.candidateId

            if (!canGrantVote) {
                logger.debug {
                    "Rejecting vote for ${request.candidateId}: already voted for ${metadata.votedFor}"
                }
                return RaftRPC.RequestVoteResponse(
                    term = request.term,
                    voteGranted = false
                )
            }

            // Check if candidate's log is at least as up-to-date as ours
            val (ourLastLogIndex, ourLastLogTerm) = storage.getLastLogIndexAndTerm()
            val candidateLogUpToDate = isCandidateLogUpToDate(
                candidateLastLogTerm = request.lastLogTerm,
                candidateLastLogIndex = request.lastLogIndex,
                ourLastLogTerm = ourLastLogTerm,
                ourLastLogIndex = ourLastLogIndex
            )

            if (!candidateLogUpToDate) {
                logger.debug {
                    "Rejecting vote for ${request.candidateId}: log not up-to-date " +
                            "(candidate: ${request.lastLogIndex}/${request.lastLogTerm}, " +
                            "ours: $ourLastLogIndex/$ourLastLogTerm)"
                }
                return RaftRPC.RequestVoteResponse(
                    term = request.term,
                    voteGranted = false
                )
            }

            // Grant vote!
            logger.info { "Granting vote to ${request.candidateId} in term ${request.term}" }
            metadataStore.recordVote(request.candidateId)

            // Reset election timer (we just heard from a viable candidate)
            resetElectionTimer()

            return RaftRPC.RequestVoteResponse(
                term = request.term,
                voteGranted = true
            )
        }
    }

    /**
     * Check if candidate's log is at least as up-to-date as ours
     *
     * From Raft paper section 5.4.1:
     * Raft determines which of two logs is more up-to-date by comparing
     * the index and term of the last entries in the logs. If the logs have
     * last entries with different terms, then the log with the later term
     * is more up-to-date. If the logs end with the same term, then
     * whichever log is longer is more up-to-date.
     */
    private fun isCandidateLogUpToDate(
        candidateLastLogTerm: Long,
        candidateLastLogIndex: Long,
        ourLastLogTerm: Long,
        ourLastLogIndex: Long
    ): Boolean {
        // If candidate's last log term is higher, candidate is more up-to-date
        if (candidateLastLogTerm > ourLastLogTerm) {
            return true
        }

        // If candidate's last log term is lower, candidate is less up-to-date
        if (candidateLastLogTerm < ourLastLogTerm) {
            return false
        }

        // Same term: candidate is at least as up-to-date if index >= ours
        return candidateLastLogIndex >= ourLastLogIndex
    }

    /**
     * Handle AppendEntries RPC from leader
     *
     * Implements Raft AppendEntries RPC receiver logic (Figure 2 in paper).
     * This handles both heartbeats (empty entries) and log replication.
     *
     * TODO: Implement in Milestone 5C (Log Replication)
     */
    suspend fun handleAppendEntries(request: RaftRPC.AppendEntriesRequest): RaftRPC.AppendEntriesResponse {
        return stateMutex.withLock {
            val currentTerm = getCurrentTerm()

            logger.trace {
                "HandleAppendEntries from ${request.leaderId} " +
                        "(term=${request.term} vs our $currentTerm, entries=${request.entries.size})"
            }

            // Rule 1: Reply false if term < currentTerm
            if (request.term < currentTerm) {
                logger.debug {
                    "Rejecting AppendEntries from ${request.leaderId}: " +
                            "term too old (${request.term} < $currentTerm)"
                }
                return RaftRPC.AppendEntriesResponse(
                    term = currentTerm,
                    success = false
                )
            }

            // If term >= currentTerm, this is a valid leader
            if (request.term >= currentTerm) {
                // Update term if needed
                if (request.term > currentTerm) {
                    metadataStore.updateTerm(request.term)
                }

                // If we're not a follower, become one
                if (currentState.get() != RaftState.FOLLOWER) {
                    logger.info {
                        "Discovered leader ${request.leaderId} in term ${request.term}, " +
                                "becoming follower"
                    }
                    currentState.set(RaftState.FOLLOWER)
                    stopHeartbeatTimer()
                }

                // Update current leader
                currentLeader = request.leaderId

                // Reset election timer (we just heard from the leader)
                resetElectionTimer()
            }

            // For now, just accept heartbeats
            // TODO: Implement log consistency checking and entry appending in Milestone 5C
            logger.trace { "Accepted heartbeat from leader ${request.leaderId}" }

            return RaftRPC.AppendEntriesResponse(
                term = request.term,
                success = true
            )
        }
    }

    // ===== Public API =====

    /**
     * Start the Raft node
     */
    fun start() {
        logger.info { "Starting Raft node ${config.nodeId} as ${getState()}" }
        // Start election timer
        resetElectionTimer()
    }

    /**
     * Stop the Raft node
     */
    suspend fun stop() {
        logger.info { "Stopping Raft node ${config.nodeId}" }

        stopElectionTimer()
        stopHeartbeatTimer()

        scope.cancel()
    }

    override fun close() {
        runBlocking {
            stop()
        }
    }

    /**
     * Get current node status (for debugging/monitoring)
     */
    fun getStatus(): String {
        return buildString {
            appendLine("Raft Node Status:")
            appendLine("  Node ID: ${config.nodeId}")
            appendLine("  State: ${getState()}")
            appendLine("  Term: ${getCurrentTerm()}")
            appendLine("  Leader: ${getLeader() ?: "unknown"}")
            appendLine("  Commit Index: $commitIndex")
            appendLine("  Last Applied: $lastApplied")
            appendLine("  Log: first=${storage.getFirstIndex()}, last=${storage.getLastIndex()}")
            if (isLeader()) {
                appendLine("  Next Index: $nextIndex")
                appendLine("  Match Index: $matchIndex")
            }
        }
    }
}