package dolmeangi.kotlin.logserver.raft

import dolmeangi.kotlin.logserver.raft.model.NodeId
import dolmeangi.kotlin.logserver.raft.model.PeerInfo
import kotlinx.serialization.Serializable

/**
 * Raft cluster configuration
 *
 * @property nodeId This node's ID
 * @property peers All nodes in the cluster (including this node)
 * @property electionTimeoutMinMs Minimum election timeout in milliseconds
 * @property electionTimeoutMaxMs Maximum election timeout in milliseconds
 * @property heartbeatIntervalMs Heartbeat interval in milliseconds
 */
@Serializable
data class RaftConfig(
    val nodeId: NodeId,
    val peers: List<PeerInfo>,
    val electionTimeoutMinMs: Long = 150,
    val electionTimeoutMaxMs: Long = 300,
    val heartbeatIntervalMs: Long = 50
) {
    init {
        require(peers.isNotEmpty()) { "Cluster must have at least one node" }
        require(peers.any { it.nodeId == nodeId }) { "This node must be in the peer list" }
        require(electionTimeoutMinMs < electionTimeoutMaxMs) {
            "Min election timeout must be less than max"
        }
        require(heartbeatIntervalMs < electionTimeoutMinMs) {
            "Heartbeat interval must be less than election timeout"
        }

        // Check for duplicate node IDs
        val nodeIds = peers.map { it.nodeId }
        require(nodeIds.size == nodeIds.toSet().size) {
            "Duplicate node IDs found in peer list"
        }
    }

    /**
     * Get information about this node
     */
    fun getThisNode(): PeerInfo {
        return peers.first { it.nodeId == nodeId }
    }

    /**
     * Get all other nodes (excluding this node)
     */
    fun getOtherPeers(): List<PeerInfo> {
        return peers.filter { it.nodeId != nodeId }
    }

    /**
     * Get total number of nodes in cluster
     */
    fun getClusterSize(): Int = peers.size

    /**
     * Get majority quorum size (more than half)
     */
    fun getMajorityQuorum(): Int = (peers.size / 2) + 1

    /**
     * Check if we have a quorum with the given count
     */
    fun hasQuorum(count: Int): Boolean = count >= getMajorityQuorum()

    override fun toString(): String {
        return "RaftConfig(nodeId=$nodeId, clusterSize=${getClusterSize()}, " +
                "quorum=${getMajorityQuorum()}, electionTimeout=$electionTimeoutMinMs-${electionTimeoutMaxMs}ms, " +
                "heartbeat=${heartbeatIntervalMs}ms)"
    }
}