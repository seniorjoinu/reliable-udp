package net.joinu.rudp

import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NioSocket
import net.joinu.nioudp.NonBlockingUDPSocket
import net.joinu.nioudp.RECOMMENDED_CHUNK_SIZE_BYTES
import net.joinu.wirehair.Wirehair
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Data piece structure: {
 *  data: ByteArray,
 *  threadId: Long,
 *  sequenceIdx: Int,
 *  sequenceSize: Int
 * }
 */

class RUDPSocket(chunkSizeBytes: Int = RECOMMENDED_CHUNK_SIZE_BYTES) : NioSocket {
    companion object {
        init {
            //SerializationUtils.registerClass()
        }
    }

    val socket = NonBlockingUDPSocket(chunkSizeBytes)
    val connections = ConcurrentHashMap<InetSocketAddress, RUDPConnection>()
    var onMessageHandler: NetworkMessageHandler? = null

    private fun sendACK() {}

    override fun listen() {
        socket.onMessage { bytes, from ->

            // TODO: handle ACK

            val buffer = ByteBuffer.wrap(bytes)
            val block = RepairBlock.deserialize(buffer)

            val connection = connections.getOrPut(from) { RUDPConnection(from) }
            val decoder =
                connection.decoders.getOrPut(block.threadId) { Wirehair.Decoder(block.messageBytes, block.blockBytes) }

            val enough = decoder.decode(block.blockId, block.data, block.writeLen)

            if (enough) {
                sendACK()
                val message = ByteArray(block.messageBytes)
                decoder.recover(message, block.messageBytes)
                decoder.close()

                // TODO: valid cleanup
                // TODO: handle blocks after sent ACK

                onMessageHandler?.invoke(message, from)
            }
        }
        socket.listen()
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        // get existing connection or create new

        // start send coroutine and add it's handle to map
        // fec
        // add metadata to each repair block
        // send WINDOW SIZE of repair blocks
        // wait FLOOD CONTROL TIMEOUT (FCT) or TRANSMISSION TIMEOUT (TRT)
        // if FCT - send another WINDOW SIZE of repair blocks
        // if TRT - throw
        // update congestion index
    }

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler
    }

    override fun getSocketState() = socket.getSocketState()

    override fun close() = socket.close()

    override fun bind(address: InetSocketAddress) = socket.bind(address)
}

val LONG_SIZE_BYTES = 8
val INT_SIZE_BYTES = 4

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
            buffer.flip()
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
        val buffer = ByteBuffer.allocateDirect(writeLen + INT_SIZE_BYTES * 4 + LONG_SIZE_BYTES)

        return buffer.putLong(threadId).putInt(messageBytes).putInt(blockId).putInt(blockBytes).putInt(writeLen)
            .put(data)
    }
}
