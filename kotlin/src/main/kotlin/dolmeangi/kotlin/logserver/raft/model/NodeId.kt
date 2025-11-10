package dolmeangi.kotlin.logserver.raft.model

import kotlinx.serialization.Serializable

/**
 * Unique identifier for a Raft node
 *
 * NodeIds must be unique within a cluster.
 * Typically numbered 1, 2, 3, ... for simplicity.
 */
@Serializable
@JvmInline
value class NodeId(val id: Int) {
    init {
        require(id > 0) { "NodeId must be positive, got: $id" }
    }

    override fun toString(): String = "Node($id)"
}