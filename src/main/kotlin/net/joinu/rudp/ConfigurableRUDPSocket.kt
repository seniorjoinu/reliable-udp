package net.joinu.rudp

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import net.joinu.nioudp.AckEventHub
import net.joinu.nioudp.AsyncUDPSocket
import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet


/**
 * RUDPSocket that can be manually configured to be have as much performance as you need
 *
 * @param mtu [Int] - Maximum Transmission Unit in bytes
 */
class ConfigurableRUDPSocket(mtu: Int) {
    companion object {
        init {
            Wirehair.init()
        }
    }

    private val logger = KotlinLogging.logger("ConfigurableRUDPSocket-${Random().nextInt()}")

    val socket = AsyncUDPSocket()

    // TODO: clean up encoders and decoders eventually
    val decoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Pair<Wirehair.Decoder, Mutex>>>()
    val encoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Wirehair.Encoder>>()

    var onMessageHandler: NetworkMessageHandler? = null
    val repairBlockSizeBytes = mtu - RepairBlock.METADATA_SIZE_BYTES

    /**
     * Send some data of [Int.MAX_VALUE] max size to some recipient
     *
     * First of all we send N packets to our recipient, then we repeat two steps: wait for FCT, send WINDOW SIZE packets
     *  and constantly observe received ACKs until we receive ACK for this data entry. If TRT elapsed before we receive
     *  ACK - we throw.
     *
     * @param data [ByteBuffer] - data to send, it should be flipped and limited to correct size you want to transmit
     * @param to [InetSocketAddress] - recipient
     * @param trtTimeoutMs [Long] - Transmission Timeout ms
     *  how much time we should wait until give up
     * @param fctTimeoutMsProvider lambda returning [Long] - Flood Control Timeout ms
     *  how much time we should wait between transmit of window size bytes
     * @param windowSizeProvider lambda returning [Int] - Window Size bytes
     *  how much bytes we should send before FCT
     *
     * @throws [TransmissionTimeoutException]
     * @throws [net.joinu.wirehair.WirehairException]
     */
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

        var blockId = 1

        // handle data size less than block size
        val blockBytes = if (data.limit() < repairBlockSizeBytes)
            data.limit() / 2
        else
            repairBlockSizeBytes

        val n = data.limit() / blockBytes + 1

        val repairBlockBuffer = ByteBuffer.allocateDirect(blockBytes)

        // send N repair blocks
        while (blockId <= n) {
            throwIfTransmissionTimeoutElapsed(trtBefore, trtTimeoutMs, threadId)

            val repairBlock = encode(repairBlockBuffer, to, threadId, blockId, data, blockBytes)

            logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

            socket.send(repairBlock.serialize(), to)

            repairBlockBuffer.clear()

            blockId++
        }

        var ackReceived = false
        AckEventHub.subscribe(to, threadId) {
            ackReceived = true
            logger.trace { "Received ACK for threadId: $threadId, stopping..." }
            AckEventHub.unsubscribe(to, threadId)
        }

        // waiting FCT and send another WINDOW SIZE of repair blocks
        while (!ackReceived) {
            logger.trace { "Sleeping for FCT" }

            val fctTimeout = fctTimeoutMsProvider()
            withTimeoutOrNull(fctTimeout) {
                while (true) {
                    if (ackReceived) return@withTimeoutOrNull
                    delay(5)
                }
            }

            if (ackReceived)
                break

            val windowSize = windowSizeProvider()
            val k = windowSize / blockBytes + 1
            val prevWindowBlockId = blockId

            while (!ackReceived && blockId <= prevWindowBlockId + k) {
                throwIfTransmissionTimeoutElapsed(trtBefore, trtTimeoutMs, threadId)

                val repairBlock = encode(repairBlockBuffer, to, threadId, blockId, data, blockBytes)

                logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

                socket.send(repairBlock.serialize(), to)

                repairBlockBuffer.clear()

                blockId++
            }
        }

        logger.trace { "Transmission of threadId: $threadId is finished, cleaning up..." }

        getEncoder(to, threadId, data, blockBytes).close()
        encoders[to]?.remove(threadId)
    }

    private fun encode(
        repairBlockBuffer: ByteBuffer,
        to: InetSocketAddress,
        threadId: Long,
        blockId: Int,
        message: ByteBuffer,
        blockSizeBytes: Int
    ): RepairBlock {
        return if (message.limit() > blockSizeBytes) {
            val writeLen = getEncoder(to, threadId, message, blockSizeBytes).encode(
                blockId,
                repairBlockBuffer as DirectBuffer,
                blockSizeBytes
            )

            RepairBlock(
                repairBlockBuffer,
                writeLen,
                threadId,
                blockId,
                message.limit(),
                blockSizeBytes
            )
        } else {
            RepairBlock(
                message,
                message.limit(),
                threadId,
                blockId,
                message.limit(),
                blockSizeBytes
            )
        }
    }

    /**
     * Set message handler
     *
     * @param handler [NetworkMessageHandler] - lambda which will be executed when we receive some message
     */
    fun onMessage(handler: NetworkMessageHandler) {
        logger.trace { "onMessage handler set" }

        onMessageHandler = handler
    }

    fun getSocketState() = socket.getSocketState()

    /**
     * Closes this socket - after this you should create another one to work with
     */
    suspend fun close() {
        socket.close()
    }

    /**
     * Binds to some local address
     *
     * @param address [InetSocketAddress] - local address to bind to
     */
    suspend fun bind(address: InetSocketAddress) {
        socket.bind(address)
    }

    // TODO: clean up acks eventually
    val received = ConcurrentHashMap<InetSocketAddress, ConcurrentSkipListSet<Long>>()

    /**
     * Listens to incoming messages
     *
     * We're listening to incoming UDP packets. When packet type is ACK we put it in ACKs collection, when packet type is
     *  REPAIR we check if we already sent some ACK for this message: if yes - sent ACK one more time. Then we add new
     *  repair block to the decoder and try to recover the original message. If recovery succeeds we send ACK and execute
     *  onMessage handler.
     *
     * WARNING: onMessage handler executes on IO dispatcher by default, so make sure you do all computational stuff on
     *  another dispatcher.
     *
     * @throws [net.joinu.wirehair.WirehairException]
     */
    suspend fun listen() {
        supervisorScope {
            socket.onMessage { bytes, from ->
                val flag = parseFlag(bytes)

                when (flag) {
                    Flags.ACK -> {
                        val ack = parseACK(bytes)

                        // TODO: handle acks received after transmission end but prevent new blocks of already received transmission to count like a new transmission

                        logger.trace { "Received ACK message for threadId: $ack from: $from" }

                        AckEventHub.fire(from, ack, Flags.ACK)
                    }
                    Flags.BLOCK_ACK -> {
                        val
                    }
                    Flags.REPAIR -> {
                        val block = parseRepairBlock(bytes)

                        logger.trace { "Received REPAIR_BLOCK message for threadId: ${block.threadId} blockId: ${block.blockId} from: $from" }

                        if (packetReceived(from, block.threadId)) {
                            logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                            sendACK(block.threadId, from)
                            return@onMessage
                        }

                        val message = tryToRecover(from, block) ?: return@onMessage

                        markPacketReceived(from, block.threadId)

                        launch {
                            sendACK(block.threadId, from)
                        }

                        logger.trace { "Invoking onMessage handler" }
                        onMessageHandler?.invoke(message, from)
                    }
                    else -> logger.error { "Received invalid type of message" }
                }
            }
        }

        socket.listen()
    }

    private suspend fun tryToRecover(address: InetSocketAddress, block: RepairBlock): ByteBuffer? {
        if (block.messageBytes > block.blockBytes) {
            val (decoder, decoderMutex) = getDecoderAndMutex(address, block)

            decoderMutex.lock()

            if (decoders[address]?.containsKey(block.threadId) == true) {
                val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.writeLen)

                if (!enough) {
                    decoderMutex.unlock()
                    return null
                }

                val message = ByteBuffer.allocateDirect(block.messageBytes)
                decoder.recover(message as DirectBuffer, block.messageBytes)

                decoder.close()
                decoders[address]?.remove(block.threadId)

                decoderMutex.unlock()

                return message
            }

            if (decoderMutex.isLocked)
                decoderMutex.unlock()

            return null
        } else {
            return block.data
        }
    }

    private fun getDecoderAndMutex(address: InetSocketAddress, block: RepairBlock): Pair<Wirehair.Decoder, Mutex> {
        return decoders.getOrPut(address) { ConcurrentHashMap() }.getOrPut(block.threadId) {
            Pair(Wirehair.Decoder(block.messageBytes, block.blockBytes), Mutex())
        }
    }

    private fun getEncoder(
        address: InetSocketAddress,
        threadId: Long,
        data: ByteBuffer,
        blockSizeBytes: Int
    ): Wirehair.Encoder {
        return encoders.getOrPut(address) { ConcurrentHashMap() }.getOrPut(threadId) {
            val buffer = ByteBuffer.allocateDirect(data.limit())
            buffer.put(data)
            buffer.flip()

            Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), blockSizeBytes)
        }
    }

    private fun packetReceived(from: InetSocketAddress, threadId: Long) = received[from]?.contains(threadId) == true
    private fun markPacketReceived(from: InetSocketAddress, threadId: Long) =
        received.getOrPut(from) { ConcurrentSkipListSet() }.add(threadId)

    private fun throwIfTransmissionTimeoutElapsed(trtBefore: Long, trtTimeoutMs: Long, threadId: Long) {
        if (System.currentTimeMillis() - trtBefore > trtTimeoutMs)
            throw TransmissionTimeoutException("Transmission timeout for threadId: $threadId elapsed")
    }

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

/**
 * Exception thrown when TRT exceeds
 */
class TransmissionTimeoutException(message: String) : RuntimeException(message)

object Flags {
    const val ACK: Byte = 0
    const val REPAIR: Byte = 1
    const val BLOCK_ACK: Byte = 2
}

data class RepairBlock(
    val data: ByteBuffer,
    val writeLen: Int,
    val threadId: Long,
    val blockId: Int,
    val messageBytes: Int,
    val blockBytes: Int,
    val latencyMs: Short,
    val lossRate: Float,
    val congestionIndex: Float
) {
    companion object {
        const val METADATA_SIZE_BYTES =
            Int.SIZE_BYTES * 5 + Long.SIZE_BYTES + Byte.SIZE_BYTES + 8 * 2 + Short.SIZE_BYTES

        fun deserialize(buffer: ByteBuffer): RepairBlock {
            val threadId = buffer.long
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
        val buffer = ByteBuffer.allocateDirect(writeLen + METADATA_SIZE_BYTES)

        data.limit(writeLen)
        buffer
            .put(Flags.REPAIR)
            .putLong(threadId)
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
