package dolmeangi.kotlin.logserver.raft.model

import kotlinx.serialization.Serializable

/**
 * Information about a peer node in the Raft cluster
 *
 * @property nodeId Unique node identifier
 * @property host Hostname or IP address
 * @property clientPort Port for client connections (DKSP protocol)
 * @property raftPort Port for Raft peer-to-peer RPC
 */
@Serializable
data class PeerInfo(
    val nodeId: NodeId,
    val host: String,
    val clientPort: Int,
    val raftPort: Int
) {
    /**
     * Get client address (host:clientPort)
     */
    fun getClientAddress(): String = "$host:$clientPort"

    /**
     * Get Raft RPC address (host:raftPort)
     */
    fun getRaftAddress(): String = "$host:$raftPort"

    override fun toString(): String =
        "PeerInfo(nodeId=$nodeId, client=$host:$clientPort, raft=$host:$raftPort)"
}

/**
 * Parse peer info from string format: "nodeId@host:clientPort:raftPort"
 *
 * Example: "1@localhost:10002:10102"
 */
fun parsePeerInfo(spec: String): PeerInfo {
    val parts = spec.split("@")
    require(parts.size == 2) { "Invalid peer spec: $spec (expected format: nodeId@host:clientPort:raftPort)" }

    val nodeId = NodeId(parts[0].toIntOrNull()
        ?: throw IllegalArgumentException("Invalid nodeId in: $spec"))

    val addressParts = parts[1].split(":")
    require(addressParts.size == 3) { "Invalid address in: $spec (expected host:clientPort:raftPort)" }

    val host = addressParts[0]
    val clientPort = addressParts[1].toIntOrNull()
        ?: throw IllegalArgumentException("Invalid clientPort in: $spec")
    val raftPort = addressParts[2].toIntOrNull()
        ?: throw IllegalArgumentException("Invalid raftPort in: $spec")

    return PeerInfo(nodeId, host, clientPort, raftPort)
}