package net.joinu.rudp

import net.joinu.nioudp.QueuedDatagramPacket
import net.joinu.nioudp.RepairBlock
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.nio.ByteBuffer
import java.util.*


abstract class RUDPSendContext(
    val threadId: UUID,
    val packet: QueuedDatagramPacket,
    val repairBlockSizeBytes: Int,
    val exit: RUDPSendContext.() -> Boolean
) {
    var blockId: Int = 0
    var lastGetRepairBlocksTimestamp: Long = 0
    var ackReceived: Boolean = false

    abstract fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int): List<RepairBlock>
    abstract fun destroy()

    fun isCongestionControlTimeoutElapsed(congestionControlTimeoutMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return lastGetRepairBlocksTimestamp + now >= congestionControlTimeoutMs
    }
}

class EncodingRUDPSendContext(
    threadId: UUID,
    packet: QueuedDatagramPacket,
    repairBlockSizeBytes: Int,
    exit: RUDPSendContext.() -> Boolean
) :
    RUDPSendContext(threadId, packet, repairBlockSizeBytes, exit) {

    private val encoder: Wirehair.Encoder

    init {
        val buffer = ByteBuffer.allocateDirect(packet.data.limit())
        buffer.put(packet.data)
        buffer.flip()

        encoder = Wirehair.Encoder(buffer as DirectBuffer, buffer.limit(), repairBlockSizeBytes)
    }

    override fun destroy() {
        encoder.close()
    }

    override fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int): List<RepairBlock> {
        val k = windowSizeBytes / repairBlockSizeBytes + 1
        val prevWindowBlockId = blockId

        val repairBlocks = mutableListOf<RepairBlock>()

        while (blockId <= prevWindowBlockId + k) {
            val repairBlockBuffer = ByteBuffer.allocate(repairBlockSizeBytes)

            val writeLen = encoder.encode(blockId, repairBlockBuffer as DirectBuffer, repairBlockSizeBytes)

            blockId++

            val repairBlock = RepairBlock(
                repairBlockBuffer,
                writeLen,
                threadId,
                blockId,
                packet.data.limit(),
                repairBlockSizeBytes,
                0, 0f, 0f // TODO: get from congestion control
            )

            repairBlocks.add(repairBlock)
        }

        lastGetRepairBlocksTimestamp = System.currentTimeMillis()

        return repairBlocks
    }
}

class DummyRUDPSendContext(
    threadId: UUID,
    packet: QueuedDatagramPacket,
    repairBlockSizeBytes: Int,
    exit: RUDPSendContext.() -> Boolean
) :
    RUDPSendContext(threadId, packet, repairBlockSizeBytes, exit) {

    override fun destroy() {}

    override fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int): List<RepairBlock> {
        val k = windowSizeBytes / repairBlockSizeBytes + 1
        val prevWindowBlockId = blockId

        val repairBlocks = mutableListOf<RepairBlock>()

        while (blockId <= prevWindowBlockId + k) {
            blockId++

            val repairBlock = RepairBlock(
                packet.data,
                packet.data.limit(),
                threadId,
                blockId,
                packet.data.limit(),
                packet.data.limit(),
                0, 0f, 0f // TODO: get from congestion control
            )

            repairBlocks.add(repairBlock)
        }

        lastGetRepairBlocksTimestamp = System.currentTimeMillis()

        return repairBlocks
    }
}

abstract class RUDPReceiveContext(val threadId: UUID) {
    abstract fun tryToRecoverFrom(block: RepairBlock): ByteBuffer?
    abstract fun destroy()
}

class DecodingRUDPReceiveContext(threadId: UUID, messageSizeBytes: Int, repairBlockSizeBytes: Int) :
    RUDPReceiveContext(threadId) {

    private val decoder = Wirehair.Decoder(messageSizeBytes, repairBlockSizeBytes)

    override fun destroy() {
        decoder.close()
    }

    override fun tryToRecoverFrom(block: RepairBlock): ByteBuffer? {
        val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.actualBlockSizeBytes)

        if (!enough) return null

        val message = ByteBuffer.allocateDirect(block.messageSizeBytes)
        decoder.recover(message as DirectBuffer, block.messageSizeBytes)

        return message
    }
}

class DummyRUDPReceiveContext(threadId: UUID) : RUDPReceiveContext(threadId) {

    override fun destroy() {}

    override fun tryToRecoverFrom(block: RepairBlock): ByteBuffer? {
        return block.data
    }
}

class RUDPContextManager {
    private val sendContexts = mutableMapOf<UUID, RUDPSendContext>()
    private val receiveContexts = mutableMapOf<UUID, RUDPReceiveContext>()
    private val finishedReceiveContexts = LinkedList<Pair<Long, UUID>>()

    fun createOrGetSendContext(
        threadId: UUID,
        packet: QueuedDatagramPacket,
        repairBlockSizeBytes: Int,
        exit: RUDPSendContext.() -> Boolean
    ) =
        sendContexts.getOrPut(threadId) {
            if (packet.data.limit() > repairBlockSizeBytes)
                EncodingRUDPSendContext(threadId, packet, repairBlockSizeBytes, exit)
            else
                DummyRUDPSendContext(threadId, packet, repairBlockSizeBytes, exit)
        }

    fun destroySendContext(threadId: UUID): RUDPSendContext? {
        sendContexts[threadId]?.destroy()
        return sendContexts.remove(threadId)
    }

    fun sendContextExists(threadId: UUID) = sendContexts.containsKey(threadId)

    fun getAllSendContexts() = sendContexts.values

    fun destroyAllExitedSendContexts(): List<UUID> {
        val ids = sendContexts.values.filter { it.exit(it) }.map { it.threadId }
        ids.forEach { destroySendContext(it) }

        return ids
    }

    fun createOrGetReceiveContext(threadId: UUID, messageSizeBytes: Int, repairBlockSizeBytes: Int) =
        receiveContexts.getOrPut(threadId) {
            if (messageSizeBytes == repairBlockSizeBytes)
                DummyRUDPReceiveContext(threadId)
            else
                DecodingRUDPReceiveContext(threadId, messageSizeBytes, repairBlockSizeBytes)
        }

    fun destroyReceiveContext(threadId: UUID): RUDPReceiveContext? {
        receiveContexts[threadId]?.destroy()
        return receiveContexts.remove(threadId)
    }

    fun receiveContextExists(threadId: UUID) = receiveContexts.containsKey(threadId)

    fun getAllReceiveContexts() = receiveContexts.values

    fun markReceiveContextAsFinished(threadId: UUID) {
        val now = System.currentTimeMillis()
        finishedReceiveContexts.addFirst(now to threadId)
    }

    fun cleanUpFinishedReceiveContexts(cleanUpTimeoutMs: Long) {
        val now = System.currentTimeMillis()

        while (finishedReceiveContexts.peekLast().first + cleanUpTimeoutMs >= now)
            finishedReceiveContexts.removeLast()
    }
}