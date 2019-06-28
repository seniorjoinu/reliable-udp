package net.joinu.rudp

import kotlinx.coroutines.CancellableContinuation
import net.joinu.rudp.SocketState.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

/**
 * Socket is created in [UNBOUND] state.
 * After [RUDPSocket.bind] call it becomes [BOUND].
 * After [RUDPSocket.close] call in becomes [CLOSED].
 *
 * Socket state change is a [synchronized] operation.
 * When state is [UNBOUND], you can only [RUDPSocket.bind].
 * When state is [BOUND], you can [RUDPSocket.runOnce], [RUDPSocket.send], [RUDPSocket.receive] and [RUDPSocket.close].
 * When state is [CLOSED] you just can't.
 */
enum class SocketState {
    UNBOUND, BOUND, CLOSED
}

/**
 * Flags which mark each possible type of data packet transmitted.
 */
object Flags {
    const val ACK: Byte = 0
    const val REPAIR: Byte = 1
    const val BLOCK_ACK: Byte = 2
}

/**
 * Representation of the ACK packet. It is sent by the receiver to the sender when receiver is able to recover the input data.
 */
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

    fun serialize(buffer: ByteBuffer) {
        buffer.put(Flags.ACK)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putFloat(congestionIndex)

        buffer.flip()
    }
}

/**
 * Representation of the BLOCK_ACK packet. It is optional and will only be used for transmission of some additional
 * congestion control data.
 */
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

    fun serialize(buffer: ByteBuffer) {
        buffer.put(Flags.BLOCK_ACK)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putInt(blockId)
            .putFloat(congestionIndex)

        buffer.flip()
    }
}

/**
 * Representation of REPAIR packet. It is sent by the sender to the receiver for each chunk of input data.
 */
data class RepairBlock(
    val data: ByteBuffer,
    val actualBlockSizeBytes: Int,
    val threadId: UUID,
    val blockId: Int,
    val messageSizeBytes: Int,
    val blockSizeBytes: Int,
    val latencyMs: Short,
    val lossRate: Float,
    val congestionIndex: Float
) {
    companion object {
        const val METADATA_SIZE_BYTES =
            Int.SIZE_BYTES * 4 + UUID_SIZE_BYTES + Byte.SIZE_BYTES + FLOAT_SIZE_BYTES * 2 + Short.SIZE_BYTES

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

    fun serialize(buffer: ByteBuffer) {
        data.limit(actualBlockSizeBytes)
        buffer.put(Flags.REPAIR)
            .putLong(threadId.mostSignificantBits)
            .putLong(threadId.leastSignificantBits)
            .putInt(messageSizeBytes)
            .putInt(blockId)
            .putInt(blockSizeBytes)
            .putShort(latencyMs)
            .putFloat(lossRate)
            .putFloat(congestionIndex)
            .putInt(actualBlockSizeBytes)
            .put(data)

        buffer.flip()
    }
}

// Some missing data sizes.
const val FLOAT_SIZE_BYTES = 8
const val UUID_SIZE_BYTES = 16

data class QueuedDatagramPacket(val data: ByteBuffer, val address: InetSocketAddress)
typealias PacketAndContinuation = Pair<QueuedDatagramPacket, CancellableContinuation<RUDPSendContext>>
