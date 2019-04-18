package net.joinu.rudp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NonBlockingUDPSocket
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet


class ConfigurableRUDPSocket(mtu: Int) {
    companion object {
        init {
            Wirehair.init()
        }
    }

    private val logger = KotlinLogging.logger("ConfigurableRUDPSocket-${Random().nextInt()}")

    val socket = NonBlockingUDPSocket()
    val decoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Pair<Wirehair.Decoder, Mutex>>>()
    val encoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Wirehair.Encoder>>()
    // TODO: clean up acks eventually
    val acks = ConcurrentHashMap<InetSocketAddress, ConcurrentSkipListSet<Long>>()
    var onMessageHandler: NetworkMessageHandler? = null
    val repairBlockSizeBytes = mtu - RepairBlock.METADATA_SIZE_BYTES

    suspend fun listen() {
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

                    if (acks[from]?.contains(block.threadId) == true) {
                        logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                        sendACK(block.threadId, from)
                        return@onMessage
                    }

                    val (decoder, decoderMutex) = getDecoder(from, block)

                    decoderMutex.lock()

                    if (decoders[from]?.containsKey(block.threadId) == true) {
                        val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.writeLen)

                        if (!enough) {
                            decoderMutex.unlock()
                            return@onMessage
                        }

                        val message = ByteBuffer.allocateDirect(block.messageBytes)
                        decoder.recover(message as DirectBuffer, block.messageBytes)

                        decoder.close()
                        decoders[from]?.remove(block.threadId)

                        decoderMutex.unlock()

                        sendACK(block.threadId, from)

                        // this will start in the same thread?
                        onMessageHandler?.invoke(message, from)
                    }

                    if (decoderMutex.isLocked)
                        decoderMutex.unlock()
                }
                else -> logger.error { "Received invalid type of message" }
            }
        }

        socket.listen()
    }

    private fun getDecoder(address: InetSocketAddress, block: RepairBlock): Pair<Wirehair.Decoder, Mutex> {
        return decoders.getOrPut(address) { ConcurrentHashMap() }.getOrPut(block.threadId) {
            Pair(Wirehair.Decoder(block.messageBytes, block.blockBytes), Mutex())
        }
    }

    private fun getEncoder(
        address: InetSocketAddress,
        threadId: Long,
        data: ByteBuffer
    ): Wirehair.Encoder {
        return encoders.getOrPut(address) { ConcurrentHashMap() }.getOrPut(threadId) {
            val buffer = ByteBuffer.allocateDirect(data.limit())
            buffer.put(data)
            buffer.flip()

            Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), repairBlockSizeBytes)
        }
    }

    suspend fun send(
        data: ByteBuffer,
        to: InetSocketAddress,
        trtTimeoutMs: Long = 15000,
        fctTimeoutMsProvider: () -> Long,
        windowSizeProvider: () -> Int
    ) {
        val threadId = Random().nextLong()

        logger.trace { "Transmission for threadId: $threadId is started" }

        val trtBefore = System.currentTimeMillis()

        val n = data.limit() / repairBlockSizeBytes + 1
        var blockId = 1

        // send N repair blocks
        while (!socket.isClosed() && blockId <= n) {
            throwIfTransmissionTimeoutElapsed(trtBefore, trtTimeoutMs, threadId)

            val repairBlockBytes = ByteBuffer.allocateDirect(repairBlockSizeBytes)

            val encoder = getEncoder(to, threadId, data)

            val writeLen = encoder.encode(blockId, repairBlockBytes as DirectBuffer, repairBlockSizeBytes)

            val repairBlock = RepairBlock(
                repairBlockBytes,
                writeLen,
                threadId,
                blockId,
                data.limit(),
                repairBlockSizeBytes
            )

            logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

            socket.send(repairBlock.serialize(), to)

            blockId++
        }

        // waiting FCT and send another WINDOW SIZE of repair blocks

        var ackReceived = false

        while (!socket.isClosed() && !ackReceived) {
            logger.trace { "Sleeping for FCT" }

            val fctTimeout = fctTimeoutMsProvider()
            ackReceived = withTimeoutOrNull(fctTimeout) {
                while (true) {
                    ackReceived(to, threadId)
                    delay(10)
                }
            } != null

            if (ackReceived) {
                logger.trace { "Received ACK for threadId: $threadId, stopping..." }
                break
            }

            val windowSize = windowSizeProvider()
            val k = windowSize / repairBlockSizeBytes + 1
            val prevWindowBlockId = blockId

            while (!socket.isClosed() && blockId <= prevWindowBlockId + k) {
                if (ackReceived(to, threadId)) {
                    logger.trace { "Received ACK for threadId: $threadId, stopping..." }
                    ackReceived = true
                    break
                }

                throwIfTransmissionTimeoutElapsed(trtBefore, trtTimeoutMs, threadId)

                val repairBlockBytes = ByteBuffer.allocateDirect(repairBlockSizeBytes)

                val writeLen = getEncoder(to, threadId, data).encode(
                    blockId,
                    repairBlockBytes as DirectBuffer,
                    repairBlockSizeBytes
                )

                val repairBlock = RepairBlock(
                    repairBlockBytes,
                    writeLen,
                    threadId,
                    blockId,
                    data.limit(),
                    repairBlockSizeBytes
                )

                logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

                socket.send(repairBlock.serialize(), to)

                blockId++
            }
        }

        logger.trace { "Transmission of threadId: $threadId is finished, cleaning up..." }

        getEncoder(to, threadId, data).close()
        encoders[to]?.remove(threadId)
    }

    private fun ackReceived(from: InetSocketAddress, threadId: Long) = acks[from]?.contains(threadId) == true

    private fun throwIfTransmissionTimeoutElapsed(trtBefore: Long, trtTimeoutMs: Long, threadId: Long) {
        if (System.currentTimeMillis() - trtBefore > trtTimeoutMs)
            throw TransmissionTimeoutException("Transmission timeout for threadId: $threadId elapsed")
    }

    fun onMessage(handler: NetworkMessageHandler) {
        logger.trace { "onMessage handler set" }

        onMessageHandler = handler
    }

    fun getSocketState() = socket.getSocketState()

    suspend fun close() = socket.close()

    suspend fun bind(address: InetSocketAddress) = socket.bind(address)

    private fun parseRepairBlock(buffer: ByteBuffer) = RepairBlock.deserialize(buffer)
    private fun parseACK(buffer: ByteBuffer) = buffer.long
    private fun parseFlag(buffer: ByteBuffer) = buffer.get()

    private suspend fun sendACK(threadId: Long, to: InetSocketAddress) {
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

            return RepairBlock(data, writeLen, threadId, blockId, messageBytes, blockBytes)
        }
    }

    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(writeLen + METADATA_SIZE_BYTES)

        data.limit(writeLen)
        buffer
            .put(Flags.REPAIR)
            .putLong(threadId)
            .putInt(messageBytes)
            .putInt(blockId)
            .putInt(blockBytes)
            .putInt(writeLen)
            .put(data)

        buffer.flip()

        return buffer
    }
}

fun ByteBuffer.toStringBytes(): String {
    val t = ByteArray(this.limit())
    this.duplicate().get(t)

    return t.joinToString { String.format("%02X", it) }
}

fun checkTilTimeElapse(timeMs: Long, block: () -> Boolean): Boolean {
    val before = System.currentTimeMillis()
    while (true) {
        if (block()) return true

        val after = System.currentTimeMillis()

        if (after - before >= timeMs) return false
    }
}
