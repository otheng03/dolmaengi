package com.raft.core

/**
 * Persistent state on all servers (must survive crashes).
 * Updated on stable storage before responding to RPCs.
 */
data class PersistentState(
    var currentTerm: Term = 0,
    var votedFor: NodeId? = null,
    val log: MutableList<LogEntry> = mutableListOf()
) {
    fun lastLogIndex(): LogIndex = log.lastOrNull()?.index ?: 0

    fun lastLogTerm(): Term = log.lastOrNull()?.term ?: 0

    fun getEntry(index: LogIndex): LogEntry? =
        log.find { it.index == index }

    fun getEntriesFrom(index: LogIndex): List<LogEntry> =
        log.filter { it.index >= index }

    fun appendEntries(entries: List<LogEntry>) {
        log.addAll(entries)
    }

    fun deleteEntriesFrom(index: LogIndex) {
        log.removeIf { it.index >= index }
    }
}
