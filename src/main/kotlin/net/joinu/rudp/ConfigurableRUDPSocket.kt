package net.joinu.rudp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    var state = RUDPSocketState.NEW
    val stateMutex = Mutex()

    val socket = NonBlockingUDPSocket()

    // TODO: clean up encoders and decoders eventually
    val decoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Pair<Wirehair.Decoder, Mutex>>>()
    val encoders = ConcurrentHashMap<InetSocketAddress, ConcurrentHashMap<Long, Wirehair.Encoder>>()

    // TODO: clean up acks eventually
    val acks = ConcurrentHashMap<InetSocketAddress, ConcurrentSkipListSet<Long>>()

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
        throwIfNotListening()

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

            // TODO: deadlock somewhere here

            logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

            socket.send(repairBlock.serialize(), to)

            blockId++
        }

        var ackReceived = false
        // waiting FCT and send another WINDOW SIZE of repair blocks
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

    /**
     * Set message handler
     *
     * @param handler [NetworkMessageHandler] - lambda which will be executed when we receive some message
     */
    fun onMessage(handler: NetworkMessageHandler) {
        logger.trace { "onMessage handler set" }

        onMessageHandler = handler
    }

    fun getSocketState() = state

    /**
     * Closes this socket - after this you should create another one to work with
     */
    suspend fun close() {
        stateMutex.withLock {
            throwIfNew()
            throwIfClosed()

            socket.close()
            state = RUDPSocketState.CLOSED
        }
    }

    /**
     * Binds to some local address
     *
     * @param address [InetSocketAddress] - local address to bind to
     */
    suspend fun bind(address: InetSocketAddress) {
        stateMutex.withLock {
            throwIfNotNew()

            socket.bind(address)
            state = RUDPSocketState.BOUND
        }
    }

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
        stateMutex.withLock {
            throwIfNotBound()

            socket.onMessage { bytes, from ->
                val flag = parseFlag(bytes)

                when (flag) {
                    Flags.ACK -> {
                        val ack = parseACK(bytes)

                        // TODO: handle acks received after transmission end but prevent new blocks of already received transmission to count like a new transmission

                        logger.trace { "Received ACK message for threadId: $ack from: $from" }

                        putACK(from, ack)
                    }
                    Flags.REPAIR -> {
                        val block = parseRepairBlock(bytes)

                        logger.trace { "Received REPAIR_BLOCK message for threadId: ${block.threadId} blockId: ${block.blockId} from: $from" }

                        if (ackReceived(from, block.threadId)) {
                            logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                            sendACK(block.threadId, from)
                            return@onMessage
                        }

                        val (decoder, decoderMutex) = getDecoderAndMutex(from, block)

                        decoderMutex.lock()

                        if (decoders[from]?.containsKey(block.threadId) == true) {
                            val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.writeLen)

                            if (!enough) {
                                decoderMutex.unlock()
                                return@onMessage
                            }

                            val message = ByteBuffer.allocateDirect(block.messageBytes)
                            decoder.recover(message as DirectBuffer, block.messageBytes)

                            // TODO: put ack somewhere else, because it's incorrect
                            //putACK(from, block.threadId)

                            decoder.close()
                            decoders[from]?.remove(block.threadId)

                            decoderMutex.unlock()

                            sendACK(block.threadId, from)

                            onMessageHandler?.invoke(message, from)
                        }

                        if (decoderMutex.isLocked)
                            decoderMutex.unlock()
                    }
                    else -> logger.error { "Received invalid type of message" }
                }
            }

            state = RUDPSocketState.LISTENING
        }

        socket.listen()
    }

    private fun getDecoderAndMutex(address: InetSocketAddress, block: RepairBlock): Pair<Wirehair.Decoder, Mutex> {
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

    private fun ackReceived(from: InetSocketAddress, threadId: Long) = acks[from]?.contains(threadId) == true
    private fun putACK(from: InetSocketAddress, threadId: Long) =
        acks.getOrPut(from) { ConcurrentSkipListSet() }.add(threadId)

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

    private fun throwIfNew() {
        if (state == RUDPSocketState.NEW) error("RUDPSocket is in NEW state")
    }

    private fun throwIfClosed() {
        if (state == RUDPSocketState.CLOSED) error("RUDPSocket is in CLOSED state")
    }

    private fun throwIfNotBound() {
        if (state != RUDPSocketState.BOUND) error("RUDPSocket is not in BOUND state")
    }

    private fun throwIfNotListening() {
        if (state != RUDPSocketState.LISTENING) error("RUDPSocket is not in LISTENING state")
    }

    private fun throwIfNotNew() {
        if (state != RUDPSocketState.NEW) error("RUDPSocket is not in NEW state")
    }
}

/**
 * Exception thrown when TRT exceeds
 */
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

object RUDPSocketState {
    const val NEW = 0
    const val BOUND = 1
    const val LISTENING = 2
    const val CLOSED = 3
}
