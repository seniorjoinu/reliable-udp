package net.joinu.rudp

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import mu.KotlinLogging
import net.joinu.nioudp.*
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue


class RUDPStorage(val cleanUpAfterElapsedMs: Long) {
    val sendThreads = ConcurrentHashMap<UUID, InetSocketAddress>()
    val receiveThreads = ConcurrentHashMap<UUID, InetSocketAddress>()
    val finishedReceiveThreads = ConcurrentLinkedQueue<Pair<Long, UUID>>()

    val decoders = ConcurrentHashMap<UUID, Pair<Wirehair.Decoder, Mutex>>()

    fun sendThreadStart(address: InetSocketAddress): UUID {
        val uuid = UUID.randomUUID()
        sendThreads[uuid] = address

        return uuid
    }

    fun sendThreadEnd(threadId: UUID) = sendThreads.remove(threadId)

    fun isThreadConsideredReceived(threadId: UUID) = finishedReceiveThreads.find { it.second == threadId } != null

    fun isSendThreadInProcess(threadId: UUID) = sendThreads.containsKey(threadId)

    fun receiveThreadStart(address: InetSocketAddress, threadId: UUID) {
        receiveThreads[threadId] = address

        cleanUpFinishedReceiveThreads()
    }

    fun receiveThreadEnd(threadId: UUID): InetSocketAddress? {
        val addr = receiveThreads.remove(threadId)
        if (addr != null)
            finishedReceiveThreads.add(System.currentTimeMillis() to threadId)

        return addr
    }

    fun isReceiveThreadInProcess(threadId: UUID) = receiveThreads.containsKey(threadId)

    fun getDecoder(threadId: UUID) = decoders[threadId]

    fun putDecoder(repairBlock: RepairBlock): Pair<Wirehair.Decoder, Mutex> {
        val decoder = Pair(Wirehair.Decoder(repairBlock.messageBytes, repairBlock.blockBytes), Mutex())

        decoders[repairBlock.threadId] = decoder

        return Pair(decoder.first, decoder.second)
    }

    fun removeDecoder(threadId: UUID): Pair<Wirehair.Decoder, Mutex>? {
        val decoder = decoders[threadId]
        decoder?.first?.close()

        return decoders.remove(threadId)
    }

    private fun cleanUpFinishedReceiveThreads() {
        val now = System.currentTimeMillis()

        while (finishedReceiveThreads.isNotEmpty()) {
            val lastEntry = finishedReceiveThreads.peek()
            if (lastEntry.first + cleanUpAfterElapsedMs > now) return

            finishedReceiveThreads.remove()
        }
    }
}

class RUDPSocket(mtuBytes: Int, cleanUpAfterElapsedMs: Long = 1000 * 60 * 10) {
    companion object {
        var count = 0

        init {
            Wirehair.init()
        }
    }

    var onMessageHandler: NetworkMessageHandler? = null

    private val logger = KotlinLogging.logger("RUDPSocket-${++count}")

    val storage = RUDPStorage(cleanUpAfterElapsedMs)
    val socket = QueuedUDPSocket(mtuBytes)
    val repairBlockSizeBytes = mtuBytes - RepairBlock.METADATA_SIZE_BYTES

    suspend fun send(data: ByteBuffer, to: InetSocketAddress, stop: () -> Boolean = { false }) {
        val threadId = storage.sendThreadStart(to)

        logger.trace { "Transmission for threadId: $threadId is started" }

        var ackReceived = false
        AckEventHub.subscribeAck(threadId) {
            ackReceived = true
            logger.trace { "Received ACK for threadId: $threadId, stopping..." }
            AckEventHub.unsubscribeAck(threadId)
            storage.sendThreadEnd(threadId)
        }

        AckEventHub.subscribeBlockAck(threadId) { /* TODO: handle congestion control tune */ }

        if (data.limit() > repairBlockSizeBytes) {
            val repairBlockBuffer = ByteBuffer.allocateDirect(repairBlockSizeBytes)
            sendEncodingLoop(data, to, repairBlockBuffer, threadId) { ackReceived || stop() }
        } else
            sendDummyLoop(data, to, threadId) { ackReceived || stop() }

        AckEventHub.unsubscribeBlockAck(threadId)
    }

    suspend fun listen(on: InetSocketAddress) {
        socket.listen(on)

        listenLoop@ while (true) {
            val packet = socket.receiveBlocking { socket.isClosed() } // "!!" means that I'm going to wait forever

            if (packet == null) {
                logger.trace { "Socket is closed, exiting..." }
                return
            }

            val (bytes, from) = packet

            when (parseFlag(bytes)) {
                Flags.ACK -> {
                    val ack = parseAck(bytes)

                    logger.trace { "Received ACK message for threadId: ${ack.threadId} from: $from" }

                    AckEventHub.fireAck(ack)
                }
                Flags.BLOCK_ACK -> {
                    val blockAck = parseBlockAck(bytes)

                    logger.trace { "Received BLOCK_ACK message for threadId: ${blockAck.threadId} from: $from" }

                    AckEventHub.fireBlockAck(blockAck)
                }
                Flags.REPAIR -> {
                    val block = parseRepairBlock(bytes)

                    logger.trace { "Received REPAIR_BLOCK message for threadId: ${block.threadId} blockId: ${block.blockId} from: $from" }

                    if (storage.isThreadConsideredReceived(block.threadId)) {
                        logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                        sendAck(block.threadId, from)
                        continue@listenLoop
                    } else if (!storage.isReceiveThreadInProcess(block.threadId)) {
                        storage.receiveThreadStart(from, block.threadId)
                    }

                    sendBlockAck(block.threadId, block.blockId, from)

                    val message = tryToRecover(block) ?: continue@listenLoop

                    storage.receiveThreadEnd(block.threadId)

                    sendAck(block.threadId, from)

                    logger.trace { "Invoking onMessage handler" }
                    onMessageHandler?.invoke(message, from)
                }
                else -> logger.error { "Received invalid type of message" }
            }
        }
    }

    /**
     * Closes this socket - after this you should create another one to work with
     */
    fun close() {
        socket.close()
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

    private suspend fun tryToRecover(block: RepairBlock): ByteBuffer? {
        if (block.messageBytes == block.blockBytes)
            return block.data

        val (decoder, decoderMutex) = if (storage.getDecoder(block.threadId) == null)
            storage.putDecoder(block)
        else
            storage.getDecoder(block.threadId)!!

        decoderMutex.lock()

        if (storage.getDecoder(block.threadId) != null) {
            val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.writeLen)

            if (!enough) {
                decoderMutex.unlock()
                return null
            }

            val message = ByteBuffer.allocateDirect(block.messageBytes)
            decoder.recover(message as DirectBuffer, block.messageBytes)

            storage.removeDecoder(block.threadId)

            decoderMutex.unlock()

            return message
        }

        if (decoderMutex.isLocked)
            decoderMutex.unlock()

        return null
    }

    private fun sendAck(threadId: UUID, to: InetSocketAddress) {
        logger.trace { "Sending ACK for threadId: $threadId to $to" }

        val ack = Ack(threadId, 0F) // TODO: get from congestion index

        socket.send(ack.serialize(), to)
    }

    private fun sendBlockAck(threadId: UUID, blockId: Int, to: InetSocketAddress) {
        logger.trace { "Sending BLOCK_ACK for threadId: $threadId to $to" }

        val blockAck = BlockAck(threadId, blockId, 0F) // TODO: get from congestion index

        socket.send(blockAck.serialize(), to)
    }

    private fun parseRepairBlock(buffer: ByteBuffer) = RepairBlock.deserialize(buffer)
    private fun parseAck(buffer: ByteBuffer) = Ack.deserialize(buffer)
    private fun parseBlockAck(buffer: ByteBuffer) = BlockAck.deserialize(buffer)
    private fun parseFlag(buffer: ByteBuffer) = buffer.get()

    private suspend fun sendEncodingLoop(
        data: ByteBuffer,
        to: InetSocketAddress,
        repairBlockBuffer: ByteBuffer,
        threadId: UUID,
        stop: () -> Boolean
    ) {
        val buffer = ByteBuffer.allocateDirect(data.limit())
        buffer.put(data)
        buffer.flip()

        val encoder = Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), repairBlockSizeBytes)

        var blockId = 1

        while (!stop()) {
            val windowSize = repairBlockSizeBytes * 2 // TODO: get from congestion control
            val k = windowSize / repairBlockSizeBytes + 1
            val prevWindowBlockId = blockId

            while (blockId <= prevWindowBlockId + k) {
                val writeLen = encoder.encode(blockId, repairBlockBuffer as DirectBuffer, repairBlockSizeBytes)

                val repairBlock = RepairBlock(
                    repairBlockBuffer,
                    writeLen,
                    threadId,
                    blockId,
                    data.limit(),
                    repairBlockSizeBytes,
                    0, 0f, 0f // TODO: get from congestion control
                )
                logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

                socket.send(repairBlock.serialize(), to)

                repairBlockBuffer.clear()

                blockId++
            }

            logger.trace { "Sleeping for CCT" }
            delay(1) // TODO: sleep congestion control timeout
        }

        encoder.close()
    }

    private suspend fun sendDummyLoop(data: ByteBuffer, to: InetSocketAddress, threadId: UUID, stop: () -> Boolean) {
        var blockId = 1

        while (!stop()) {
            val k = 1
            val prevWindowBlockId = blockId

            while (blockId <= prevWindowBlockId + k) {

                val repairBlock = RepairBlock(
                    data,
                    data.limit(),
                    threadId,
                    blockId,
                    data.limit(),
                    data.limit(),
                    0, 0f, 0f // TODO: get from congestion control
                )
                logger.trace { "Sending REPAIR_BLOCK threadId: $threadId, blockId: $blockId to $to" }

                socket.send(repairBlock.serialize(), to)

                blockId++
            }

            logger.trace { "Sleeping for CCT" }
            delay(1) // TODO: sleep congestion control timeout
        }
    }
}
