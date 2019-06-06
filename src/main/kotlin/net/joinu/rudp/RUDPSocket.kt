package net.joinu.rudp

import kotlinx.coroutines.sync.Mutex
import mu.KotlinLogging
import net.joinu.nioudp.AckEventHub
import net.joinu.nioudp.QueuedUDPSocket
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap


class RUDPStorage {
    val sendThreads = ConcurrentHashMap<UUID, Pair<InetSocketAddress, Long>>()
    val receiveThreads = ConcurrentHashMap<UUID, Pair<InetSocketAddress, Long>>()

    val decoders = ConcurrentHashMap<UUID, Pair<Wirehair.Decoder, Mutex>>()

    fun sendStart(address: InetSocketAddress, threadId: Long): UUID {
        val uuid = UUID.randomUUID()
        sendThreads[uuid] = Pair(address, threadId)

        return uuid
    }

    fun sendEnd(uuid: UUID) = sendThreads.remove(uuid)

    fun receiveStart(address: InetSocketAddress, threadId: Long): UUID {
        val uuid = UUID.randomUUID()
        receiveThreads[uuid] = Pair(address, threadId)

        return uuid
    }

    fun receiveEnd(uuid: UUID) = receiveThreads.remove(uuid)

/*    fun getEncoder(uuid: UUID) = encoders[uuid]

    fun putEncoder(data: ByteBuffer, blockSizeBytes: Int): Pair<UUID, Wirehair.Encoder> {
        val uuid = UUID.randomUUID()
        val buffer = ByteBuffer.allocateDirect(data.limit())
        buffer.put(data)
        buffer.flip()

        val encoder = Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), blockSizeBytes)
        encoders[uuid] = encoder

        return Pair(uuid, encoder)
    }

    fun removeEncoder(uuid: UUID): Wirehair.Encoder? {
        encoders[uuid]?.close()

        return encoders.remove(uuid)
    }*/

    fun getDecoder(uuid: UUID) = decoders[uuid]

    fun putDecoder(repairBlock: RepairBlock): Triple<UUID, Wirehair.Decoder, Mutex> {
        val decoder = Pair(Wirehair.Decoder(repairBlock.messageBytes, repairBlock.blockBytes), Mutex())
        val uuid = UUID.randomUUID()

        decoders[uuid] = decoder

        return Triple(uuid, decoder.first, decoder.second)
    }

    fun removeDecoder(uuid: UUID): Pair<Wirehair.Decoder, Mutex>? {
        val decoder = decoders[uuid]
        decoder?.first?.close()
        if (decoder?.second?.isLocked == true)
            decoder.second.unlock()

        return decoders.remove(uuid)
    }
}

class RUDPSocket(mtuBytes: Int) {
    companion object {
        var count = 0

        init {
            Wirehair.init()
        }
    }

    private val logger = KotlinLogging.logger("RUDPSocket-${++count}")

    val storage = RUDPStorage()
    val socket = QueuedUDPSocket(mtuBytes)
    val repairBlockSizeBytes = mtuBytes - RepairBlock.METADATA_SIZE_BYTES

    fun send(data: ByteBuffer, to: InetSocketAddress, stop: () -> Boolean = { false }) {
        val threadId = Random().nextLong()

        logger.trace { "Transmission for threadId: $threadId is started" }

        var ackReceived = false
        AckEventHub.subscribe(to, threadId, Flags.ACK) {
            ackReceived = true
            logger.trace { "Received ACK for threadId: $threadId, stopping..." }
            AckEventHub.unsubscribe(to, threadId, Flags.ACK)
        }

        AckEventHub.subscribe(to, threadId, Flags.BLOCK_ACK) { /* TODO: handle congestion control tune */ }

        if (data.limit() > repairBlockSizeBytes) {
            val repairBlockBuffer = ByteBuffer.allocateDirect(repairBlockSizeBytes)
            sendEncodingLoop(data, to, repairBlockBuffer, threadId) { ackReceived || stop() }
        } else
            sendDummyLoop(data, to, threadId) { ackReceived || stop() }

        AckEventHub.unsubscribe(to, threadId, Flags.BLOCK_ACK)
    }

    private fun sendEncodingLoop(
        data: ByteBuffer,
        to: InetSocketAddress,
        repairBlockBuffer: ByteBuffer,
        threadId: Long,
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
            // TODO: sleep congestion control timeout
        }

        encoder.close()
    }

    private fun sendDummyLoop(data: ByteBuffer, to: InetSocketAddress, threadId: Long, stop: () -> Boolean) {
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
            // TODO: sleep congestion control timeout
        }
    }
}
