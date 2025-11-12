package com.raft.statemachine

import com.raft.node.StateMachine
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple key-value store state machine.
 */
class KeyValueStore : StateMachine {
    private val store = ConcurrentHashMap<String, String>()

    override fun apply(command: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(command)
        val op = buffer.get()

        return when (op.toInt()) {
            OP_PUT -> {
                val key = readString(buffer)
                val value = readString(buffer)
                store[key] = value
                "OK".toByteArray()
            }
            OP_GET -> {
                val key = readString(buffer)
                (store[key] ?: "").toByteArray()
            }
            OP_DELETE -> {
                val key = readString(buffer)
                store.remove(key)
                "OK".toByteArray()
            }
            else -> "UNKNOWN".toByteArray()
        }
    }

    fun get(key: String): String? = store[key]

    fun getAll(): Map<String, String> = store.toMap()

    private fun readString(buffer: ByteBuffer): String {
        val len = buffer.int
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return String(bytes)
    }

    companion object {
        const val OP_PUT = 1
        const val OP_GET = 2
        const val OP_DELETE = 3

        fun putCommand(key: String, value: String): ByteArray {
            val keyBytes = key.toByteArray()
            val valueBytes = value.toByteArray()
            return ByteBuffer.allocate(1 + 4 + keyBytes.size + 4 + valueBytes.size)
                .put(OP_PUT.toByte())
                .putInt(keyBytes.size)
                .put(keyBytes)
                .putInt(valueBytes.size)
                .put(valueBytes)
                .array()
        }

        fun getCommand(key: String): ByteArray {
            val keyBytes = key.toByteArray()
            return ByteBuffer.allocate(1 + 4 + keyBytes.size)
                .put(OP_GET.toByte())
                .putInt(keyBytes.size)
                .put(keyBytes)
                .array()
        }

        fun deleteCommand(key: String): ByteArray {
            val keyBytes = key.toByteArray()
            return ByteBuffer.allocate(1 + 4 + keyBytes.size)
                .put(OP_DELETE.toByte())
                .putInt(keyBytes.size)
                .put(keyBytes)
                .array()
        }
    }
}
