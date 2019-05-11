package net.joinu.nioudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer

typealias NetworkMessageHandler = suspend (buffer: ByteBuffer, from: InetSocketAddress) -> Unit

enum class SocketState {
    UNBOUND, BOUND, LISTENING, CLOSED
}

const val MAX_CHUNK_SIZE_BYTES = 65_507
const val RECOMMENDED_CHUNK_SIZE_BYTES = 504
const val DATA_SIZE_BYTES = 4


object AckEventHub {
    val subscribers = mutableMapOf<InetSocketAddress, MutableMap<Long, () -> Unit>>()

    fun subscribe(address: InetSocketAddress, threadId: Long, block: () -> Unit) {
        val blocks = subscribers.getOrPut(address) { mutableMapOf() }
        blocks[threadId] = block
    }

    fun unsubscribe(address: InetSocketAddress, threadId: Long) {
        subscribers[address]?.remove(threadId)
    }

    fun fire(address: InetSocketAddress, threadId: Long) {
        subscribers[address]?.get(threadId)?.invoke()
    }
}