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

data class Ack(val threadId: Long, val congestionIndex: Float)
data class BlockAck(val threadId: Long, val blockId: Int, val congestionIndex: Float)

object AckEventHub {
    val subscribers = mutableMapOf<InetSocketAddress, MutableMap<Long, () -> Unit>>()

    fun subscribeAck(address: InetSocketAddress, threadId: Long, block: () -> Unit) {
        val blocks = subscribers.getOrPut(address) { mutableMapOf() }
        blocks[threadId] = block
    }

    fun unsubscribeAck(address: InetSocketAddress, threadId: Long) {
        subscribers[address]?.remove(threadId)
    }

    fun fireAck(address: InetSocketAddress, threadId: Long) {
        subscribers[address]?.get(threadId)?.invoke()
    }

    fun subscribeBlockAck(
        address: InetSocketAddress,
        threadId: Long,
        blockId: Int: () ->) // TODO: fix this according to new [Ack] and [BlockAck]
}
