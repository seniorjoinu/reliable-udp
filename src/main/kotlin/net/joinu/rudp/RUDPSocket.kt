package net.joinu.rudp

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
    val socket = NonBlockingUDPSocket(RECOMMENDED_CHUNK_SIZE_BYTES)
    val connections = ConcurrentHashMap<InetSocketAddress, RUDPConnection>()
    var onMessageHandler: NetworkMessageHandler? = null
    var acks = ConcurrentHashMap<InetSocketAddress, ConcurrentSkipListSet<Long>>()

    override fun listen() {
        socket.onMessage { bytes, from ->
            val buffer = ByteBuffer.wrap(bytes)

            val flag = parseFlag(buffer)

            when (flag) {
                Flags.ACK -> {
                    val ack = parseACK(buffer)
                    acks.getOrPut(from) { ConcurrentSkipListSet() }.add(ack)
                }
                Flags.REPAIR -> {
                    val block = parseRepairBlock(buffer)

                    val connection = connections.getOrPut(from) { RUDPConnection(from) }

                    if (acks[from]?.contains(block.threadId) == true) {
                        println("Received a block for already received value")
                        sendACK(block.threadId, from)
                        return@onMessage
                    }

                    val decoder = connection.decoders.getOrPut(block.threadId) {
                        Wirehair.Decoder(block.messageBytes, block.blockBytes)
                    }

                    val enough = decoder.decode(block.blockId, block.data, block.writeLen)

                    if (!enough) return@onMessage

                    sendACK(block.threadId, from)
                    val message = ByteArray(block.messageBytes)
                    decoder.recover(message, block.messageBytes)
                    decoder.close()
                    connections[from]?.decoders?.remove(block.threadId)

                    onMessageHandler?.invoke(message, from)
                }
                else -> error("Invalid message type received")
            }
        }

        socket.listen()
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        val connection = connections.getOrPut(to) { RUDPConnection(to) }
        val threadId = Random().nextLong()
        val encoder = connection.encoders.getOrPut(threadId) {
            val buffer = ByteBuffer.allocateDirect(data.size)
            buffer.put(data)

            Wirehair.Encoder(buffer as DirectBuffer, data.size, RECOMMENDED_CHUNK_SIZE_BYTES)
        }

        val trtTimeoutMs = 30000
        val trtBefore = System.currentTimeMillis()

        var blockId = 1
        while (!socket.isClosed()) {
            if (acks[to]?.contains(threadId) == true) break
            if (System.currentTimeMillis() - trtBefore > trtTimeoutMs) error("Transmission Timeout elapsed")

            // TODO: handle WINDOW SIZE
            val repairBlockBytes = ByteArray(RECOMMENDED_CHUNK_SIZE_BYTES)
            val writeLen = encoder.encode(blockId, repairBlockBytes, RECOMMENDED_CHUNK_SIZE_BYTES)

            val repairBlock = RepairBlock(
                repairBlockBytes,
                writeLen,
                threadId,
                blockId,
                data.size,
                RECOMMENDED_CHUNK_SIZE_BYTES
            )

            socket.send(repairBlock.serialize().array(), to)

            blockId++

            // TODO: wait FCT
        }

        encoder.close()
        connections[to]?.encoders?.remove(threadId)
    }

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler
    }

    override fun getSocketState() = socket.getSocketState()

    override fun close() = socket.close()

    override fun bind(address: InetSocketAddress) = socket.bind(address)

    private fun parseRepairBlock(buffer: ByteBuffer) = RepairBlock.deserialize(buffer)
    private fun parseACK(buffer: ByteBuffer) = buffer.long
    private fun parseFlag(buffer: ByteBuffer) = buffer.get()

    private fun sendACK(threadId: Long, to: InetSocketAddress) {
        val ackSize = Byte.SIZE_BYTES + Long.SIZE_BYTES
        val buffer = ByteBuffer.allocateDirect(ackSize)
        buffer.put(Flags.ACK)
        buffer.putLong(threadId)
        buffer.flip()

        socket.send(buffer.array(), to)
    }
}

object Flags {
    const val ACK: Byte = 0
    const val REPAIR: Byte = 1
}

data class RepairBlock(
    val data: ByteArray,
    val writeLen: Int,
    val threadId: Long,
    val blockId: Int,
    val messageBytes: Int,
    val blockBytes: Int
) {
    companion object {
        fun deserialize(buffer: ByteBuffer): RepairBlock {
            val threadId = buffer.long
            val messageBytes = buffer.int
            val blockId = buffer.int
            val blockBytes = buffer.int
            val writeLen = buffer.int
            val data = ByteArray(writeLen)
            buffer.get(data)

            return RepairBlock(data, writeLen, threadId, blockId, messageBytes, blockBytes)
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(writeLen + Int.SIZE_BYTES * 4 + Long.SIZE_BYTES)

        buffer.putLong(threadId).putInt(messageBytes).putInt(blockId).putInt(blockBytes).putInt(writeLen).put(data)
        buffer.flip()

        return buffer
    }
}
