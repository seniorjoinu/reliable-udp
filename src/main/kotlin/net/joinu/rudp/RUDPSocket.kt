package net.joinu.rudp

import mu.KotlinLogging
import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NioSocket
import net.joinu.nioudp.NonBlockingUDPSocket
import net.joinu.nioudp.RECOMMENDED_CHUNK_SIZE_BYTES
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet


class RUDPSocket : NioSocket {
    companion object {
        init {
            Wirehair.init()
        }
    }

    private val logger = KotlinLogging.logger("RUDPSocket-${Random().nextInt()}")

    val socket = NonBlockingUDPSocket(RECOMMENDED_CHUNK_SIZE_BYTES + RepairBlock.METADATA_SIZE_BYTES)
    val connections = ConcurrentHashMap<InetSocketAddress, RUDPConnection>()
    var onMessageHandler: NetworkMessageHandler? = null
    var acks = ConcurrentHashMap<InetSocketAddress, ConcurrentSkipListSet<Long>>()

    override fun listen() {
        socket.onMessage { bytes, from ->
            val flag = parseFlag(bytes)

            when (flag) {
                Flags.ACK -> {
                    val ack = parseACK(bytes)

                    logger.trace { "Received ACK message for threadId: $ack from: $from" }

                    acks.getOrPut(from) { ConcurrentSkipListSet() }.add(ack)
                }
                Flags.REPAIR -> {
                    val block = parseRepairBlock(bytes)

                    logger.trace { "Received REPAIR_BLOCK message for threadId: ${block.threadId} blockId: ${block.blockId} from: $from" }

                    val connection = connections.getOrPut(from) { RUDPConnection(from) }

                    if (acks[from]?.contains(block.threadId) == true) {
                        logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                        sendACK(block.threadId, from)
                        return@onMessage
                    }

                    val decoder = connection.decoders.getOrPut(block.threadId) {
                        Wirehair.Decoder(block.messageBytes, block.blockBytes)
                    }

                    val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.writeLen)

                    if (!enough) return@onMessage

                    sendACK(block.threadId, from)

                    val message = ByteBuffer.allocateDirect(block.messageBytes)
                    decoder.recover(message as DirectBuffer, block.messageBytes)

                    decoder.close()
                    connections[from]?.decoders?.remove(block.threadId)

                    onMessageHandler?.invoke(message, from)
                }
                else -> logger.error { "Received invalid type of message" }
            }
        }

        socket.listen()
    }

    override fun send(data: ByteBuffer, to: InetSocketAddress) {
        val threadId = Random().nextLong()

        logger.trace { "Transmission for threadId: $threadId is started" }

        val connection = connections.getOrPut(to) { RUDPConnection(to) }
        val encoder = connection.encoders.getOrPut(threadId) {
            val buffer = ByteBuffer.allocateDirect(data.limit())
            buffer.put(data)
            buffer.flip()

            Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), RECOMMENDED_CHUNK_SIZE_BYTES)
        }

        val trtTimeoutMs = 30000
        val trtBefore = System.currentTimeMillis()

        var blockId = 1
        while (!socket.isClosed()) {
            if (acks[to]?.contains(threadId) == true) {
                logger.trace { "Received ACK for threadId: $threadId, stopping..." }
                break
            }
            if (System.currentTimeMillis() - trtBefore > trtTimeoutMs)
                throw TransmissionTimeoutException("Transmission timeout for threadId: $threadId elapsed")

            // TODO: handle WINDOW SIZE
            val repairBlockBytes = ByteBuffer.allocateDirect(RECOMMENDED_CHUNK_SIZE_BYTES)

            val writeLen = encoder.encode(blockId, repairBlockBytes as DirectBuffer, RECOMMENDED_CHUNK_SIZE_BYTES)

            val repairBlock = RepairBlock(
                repairBlockBytes,
                writeLen,
                threadId,
                blockId,
                data.limit(),
                RECOMMENDED_CHUNK_SIZE_BYTES
            )

            logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

            socket.send(repairBlock.serialize(), to)

            blockId++

            if (blockId * RECOMMENDED_CHUNK_SIZE_BYTES > data.limit())
                Thread.sleep(100)
        }

        logger.trace { "Transmission of threadId: $threadId is finished, cleaning up..." }

        encoder.close()
        connections[to]?.encoders?.remove(threadId)
    }

    override fun onMessage(handler: NetworkMessageHandler) {
        logger.trace { "onMessage handler set" }

        onMessageHandler = handler
    }

    override fun getSocketState() = socket.getSocketState()

    override fun close() = socket.close()

    override fun bind(address: InetSocketAddress) = socket.bind(address)

    private fun parseRepairBlock(buffer: ByteBuffer) = RepairBlock.deserialize(buffer)
    private fun parseACK(buffer: ByteBuffer) = buffer.long
    private fun parseFlag(buffer: ByteBuffer) = buffer.get()

    private fun sendACK(threadId: Long, to: InetSocketAddress) {
        logger.trace { "Sending ACK for threadId: $threadId to $to" }

        val ackSize = Byte.SIZE_BYTES + Long.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(ackSize)
        buffer.put(Flags.ACK)
        buffer.putLong(threadId)
        buffer.flip()

        socket.send(buffer, to)
    }
}

class TransmissionTimeoutException(message: String) : RuntimeException(message)

object Flags {
    const val ACK: Byte = 0
    const val REPAIR: Byte = 1
}

data class RepairBlock(
    val data: ByteBuffer,
    val writeLen: Int,
    val threadId: Long,
    val blockId: Int,
    val messageBytes: Int,
    val blockBytes: Int
) {
    companion object {
        const val METADATA_SIZE_BYTES = Int.SIZE_BYTES * 4 + Long.SIZE_BYTES + Byte.SIZE_BYTES

        fun deserialize(buffer: ByteBuffer): RepairBlock {
            val threadId = buffer.long
            val messageBytes = buffer.int
            val blockId = buffer.int
            val blockBytes = buffer.int
            val writeLen = buffer.int
            val data = ByteBuffer.allocateDirect(writeLen)
            data.put(buffer)
            data.flip()

            println("Deserialize - $blockId - ${data.toStringBytes()}")

            return RepairBlock(data, writeLen, threadId, blockId, messageBytes, blockBytes)
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(writeLen + METADATA_SIZE_BYTES)

        println("Serialize - $blockId - ${data.toStringBytes()}")

        data.limit(writeLen)
        buffer.put(Flags.REPAIR).putLong(threadId).putInt(messageBytes).putInt(blockId).putInt(blockBytes)
            .putInt(writeLen).put(data)
        buffer.flip()

        return buffer
    }
}

fun ByteBuffer.toStringBytes(): String {
    val t = ByteArray(this.limit())
    this.duplicate().get(t)

    return t.joinToString { String.format("%02X", it) }
}
