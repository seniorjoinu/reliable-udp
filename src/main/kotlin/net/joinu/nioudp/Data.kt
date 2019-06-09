package net.joinu.nioudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

typealias NetworkMessageHandler = suspend (buffer: ByteBuffer, from: InetSocketAddress) -> Unit

enum class SocketState {
    UNBOUND, BOUND, LISTENING, CLOSED
}

const val MAX_CHUNK_SIZE_BYTES = 65_507
const val RECOMMENDED_CHUNK_SIZE_BYTES = 504
const val DATA_SIZE_BYTES = 4

typealias AckConsumer = (ack: Ack) -> Unit
typealias BlockAckConsumer = (blockAck: BlockAck) -> Unit

object AckEventHub {
    val ackSubscribers = mutableMapOf<UUID, AckConsumer>()

    fun subscribeAck(threadId: UUID, block: AckConsumer) {
        ackSubscribers[threadId] = block
    }

    fun unsubscribeAck(threadId: UUID) {
        ackSubscribers.remove(threadId)
    }

    fun fireAck(ack: Ack) {
        ackSubscribers[ack.threadId]?.invoke(ack)
    }

    val blockAckSubscribers = mutableMapOf<UUID, BlockAckConsumer>()

    fun subscribeBlockAck(threadId: UUID, block: BlockAckConsumer) {
        blockAckSubscribers[threadId] = block
    }

    fun unsubscribeBlockAck(threadId: UUID) {
        blockAckSubscribers.remove(threadId)
    }

    fun fireBlockAck(blockAck: BlockAck) {
        blockAckSubscribers[blockAck.threadId]?.invoke(blockAck)
    }
}

/**
 * Exception thrown when TRT exceeds
 */
class TransmissionTimeoutException(message: String) : RuntimeException(message)

object Flags {
    const val ACK: Byte = 0
    const val REPAIR: Byte = 1
    const val BLOCK_ACK: Byte = 2
}

data class Ack(val threadId: UUID, val congestionIndex: Float) {
    companion object {
        const val SIZE_BYTES = UUID_SIZE_BYTES + FLOAT_SIZE_BYTES

        fun deserialize(buffer: ByteBuffer): Ack {
            val threadId1 = buffer.long
            val threadId2 = buffer.long
            val threadId = UUID(threadId1, threadId2)
            val congestionIndex = buffer.float

            return Ack(threadId, congestionIndex)
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocate(SIZE_BYTES)

        buffer.put(Flags.ACK)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putFloat(congestionIndex)

        buffer.flip()

        return buffer
    }
}

data class BlockAck(val threadId: UUID, val blockId: Int, val congestionIndex: Float) {
    companion object {
        const val SIZE_BYTES = UUID_SIZE_BYTES + Int.SIZE_BYTES + FLOAT_SIZE_BYTES

        fun deserialize(byteBuffer: ByteBuffer): BlockAck {
            val threadId1 = byteBuffer.long
            val threadId2 = byteBuffer.long
            val threadId = UUID(threadId1, threadId2)
            val blockId = byteBuffer.int
            val congestionIndex = byteBuffer.float

            return BlockAck(threadId, blockId, congestionIndex)
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocate(SIZE_BYTES)

        buffer.put(Flags.BLOCK_ACK)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putInt(blockId)
            .putFloat(congestionIndex)

        buffer.flip()

        return buffer
    }
}

data class RepairBlock(
    val data: ByteBuffer,
    val writeLen: Int,
    val threadId: UUID,
    val blockId: Int,
    val messageBytes: Int,
    val blockBytes: Int,
    val latencyMs: Short,
    val lossRate: Float,
    val congestionIndex: Float
) {
    companion object {
        const val METADATA_SIZE_BYTES =
            Int.SIZE_BYTES * 5 + UUID_SIZE_BYTES + Byte.SIZE_BYTES + FLOAT_SIZE_BYTES * 2 + Short.SIZE_BYTES

        fun deserialize(buffer: ByteBuffer): RepairBlock {
            val threadId1 = buffer.long
            val threadId2 = buffer.long
            val threadId = UUID(threadId1, threadId2)
            val messageBytes = buffer.int
            val blockId = buffer.int
            val blockBytes = buffer.int
            val latencyMs = buffer.short
            val lossRate = buffer.float
            val congestionIndex = buffer.float
            val writeLen = buffer.int
            val data = ByteBuffer.allocateDirect(writeLen)
            data.put(buffer)
            data.flip()

            return RepairBlock(
                data,
                writeLen,
                threadId,
                blockId,
                messageBytes,
                blockBytes,
                latencyMs,
                lossRate,
                congestionIndex
            )
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocate(writeLen + METADATA_SIZE_BYTES)

        data.limit(writeLen)
        buffer.put(Flags.REPAIR)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putInt(messageBytes)
            .putInt(blockId)
            .putInt(blockBytes)
            .putShort(latencyMs)
            .putFloat(lossRate)
            .putFloat(congestionIndex)
            .putInt(writeLen)
            .put(data)

        buffer.flip()

        return buffer
    }
}

const val FLOAT_SIZE_BYTES = 8
const val UUID_SIZE_BYTES = 16