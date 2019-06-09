package net.joinu.rudp

import kotlinx.coroutines.delay
import mu.KotlinLogging
import net.joinu.nioudp.*
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


class RUDPSocket(mtuBytes: Int, cleanUpTimeoutMs: Long = 1000 * 60 * 10) {
    companion object {
        var count = 0

        init {
            Wirehair.init()
        }
    }

    val sendQueue = ConcurrentLinkedQueue<Pair<QueuedDatagramPacket, () -> Boolean>>()
    val receiveQueue = ConcurrentLinkedQueue<Pair<QueuedDatagramPacket, () -> Boolean>>()

    private val logger = KotlinLogging.logger("RUDPSocket-${++count}")

    val contextManager = RUDPContextManager()
    val socket = QueuedUDPSocket(mtuBytes)
    val repairBlockSizeBytes = mtuBytes - RepairBlock.METADATA_SIZE_BYTES

    private fun processSend() {
        val contexts = contextManager.getAllSendContexts()

        contexts.asSequence()
            .filter { it.isCongestionControlTimeoutElapsed(10) } // TODO: get from CC
            .map { it.getNextWindowSizeRepairBlocks(repairBlockSizeBytes * 2) to it.packet.address } // TODO: get from CC
            .forEach { (blocks, to) ->
                blocks.forEach { } // TODO: send
            }
    }

    private fun prepareSend() {
        contextManager.destroyAllExitedSendContexts()
            .forEach { AckEventHub.unsubscribeBlockAck(it) }

        sendQueue.forEach { (packet, exit) ->
            val threadId = UUID.randomUUID()

            logger.trace { "Transmission for threadId: $threadId is started" }

            val context = contextManager.createOrGetSendContext(threadId, packet, repairBlockSizeBytes) {
                exit() || ackReceived
            }

            AckEventHub.subscribeAck(threadId) {
                context.ackReceived = true
                logger.trace { "Received ACK for threadId: $threadId, stopping..." }
                AckEventHub.unsubscribeAck(threadId)
            }

            AckEventHub.subscribeBlockAck(threadId) { /* TODO: handle congestion control tune */ }
        }
    }

    fun send(data: ByteBuffer, to: InetSocketAddress, stop: () -> Boolean = { false }) {
        sendQueue.add(QueuedDatagramPacket(data, to) to stop)
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
                    onMessageHandler.invoke(message, from)
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
        if (block.messageSizeBytes == block.blockSizeBytes)
            return block.data

        val (decoder, decoderMutex) = if (storage.getDecoder(block.threadId) == null)
            storage.putDecoder(block)
        else
            storage.getDecoder(block.threadId)!!

        decoderMutex.lock()

        if (storage.getDecoder(block.threadId) != null) {
            val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.actualBlockSizeBytes)

            if (!enough) {
                decoderMutex.unlock()
                return null
            }

            val message = ByteBuffer.allocateDirect(block.messageSizeBytes)
            decoder.recover(message as DirectBuffer, block.messageSizeBytes)

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

        buffer.clear()

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
