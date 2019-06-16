package net.joinu.rudp

import net.joinu.wirehair.Wirehair
import net.joinu.wirehair.WirehairException
import sun.nio.ch.DirectBuffer
import java.nio.ByteBuffer
import java.util.*


abstract class RUDPSendContext(
    val threadId: UUID,
    val packet: QueuedDatagramPacket,
    val repairBlockSizeBytes: Int,
    val exit: RUDPSendContext.() -> Boolean,
    val complete: RUDPSendContext.() -> Unit
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
    exit: RUDPSendContext.() -> Boolean,
    complete: RUDPSendContext.() -> Unit
) :
    RUDPSendContext(threadId, packet, repairBlockSizeBytes, exit, complete) {

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
            val repairBlockBuffer = ByteBuffer.allocateDirect(repairBlockSizeBytes)

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
    exit: RUDPSendContext.() -> Boolean,
    complete: RUDPSendContext.() -> Unit
) :
    RUDPSendContext(threadId, packet, repairBlockSizeBytes, exit, complete) {

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
        try {
            val enough = decoder.decode(block.blockId, block.data as DirectBuffer, block.actualBlockSizeBytes)

            if (!enough) return null

            val message = ByteBuffer.allocateDirect(block.messageSizeBytes)
            decoder.recover(message as DirectBuffer, block.messageSizeBytes)

            return message
        } catch (e: WirehairException) {
            return null
        }
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
    private val receiveContexts = mutableMapOf<UUID, Pair<Long, RUDPReceiveContext>>()
    private val finishedReceiveContexts = LinkedList<Pair<Long, UUID>>()

    fun createOrGetSendContext(
        threadId: UUID,
        packet: QueuedDatagramPacket,
        repairBlockSizeBytes: Int,
        exit: RUDPSendContext.() -> Boolean,
        complete: RUDPSendContext.() -> Unit
    ) =
        sendContexts.getOrPut(threadId) {
            if (packet.data.limit() > repairBlockSizeBytes)
                EncodingRUDPSendContext(threadId, packet, repairBlockSizeBytes, exit, complete)
            else
                DummyRUDPSendContext(threadId, packet, repairBlockSizeBytes, exit, complete)
        }

    fun destroySendContext(threadId: UUID): RUDPSendContext? {
        sendContexts[threadId]?.destroy()
        return sendContexts.remove(threadId)
    }

    fun sendContextExists(threadId: UUID) = sendContexts.containsKey(threadId)

    fun getSendContext(threadId: UUID) = sendContexts[threadId]

    fun getAllSendContexts() = sendContexts.values

    fun destroyAllExitedSendContexts() {
        val ids = sendContexts.values.filter { it.exit(it) }.map { it.threadId }
        ids.forEach { destroySendContext(it) }
    }

    fun createOrGetReceiveContext(threadId: UUID, messageSizeBytes: Int, repairBlockSizeBytes: Int) =
        receiveContexts.getOrPut(threadId) {
            if (messageSizeBytes == repairBlockSizeBytes)
                System.currentTimeMillis() to DummyRUDPReceiveContext(threadId)
            else
                System.currentTimeMillis() to DecodingRUDPReceiveContext(
                    threadId,
                    messageSizeBytes,
                    repairBlockSizeBytes
                )
        }.second

    fun destroyReceiveContext(threadId: UUID): RUDPReceiveContext? {
        receiveContexts[threadId]?.second?.destroy()
        return receiveContexts.remove(threadId)?.second
    }

    fun destroyAllForbiddenReceiveContexts(cleanUpTimeoutMs: Long) {
        val now = System.currentTimeMillis()
        val ids = receiveContexts.entries.filter { it.value.first + cleanUpTimeoutMs > now }.map { it.key }

        ids.forEach { destroyReceiveContext(it) }
    }

    fun updateReceiveContext(threadId: UUID) {
        if (!receiveContexts.containsKey(threadId))
            return

        val context = receiveContexts[threadId]!!
        receiveContexts[threadId] = System.currentTimeMillis() to context.second
    }

    fun getReceiveContext(threadId: UUID) = receiveContexts[threadId]

    fun receiveContextExists(threadId: UUID) = receiveContexts.containsKey(threadId)

    fun getAllReceiveContexts() = receiveContexts.values

    fun markReceiveContextAsFinished(threadId: UUID) {
        val now = System.currentTimeMillis()
        finishedReceiveContexts.addFirst(now to threadId)
    }

    fun isReceiveContextFinished(threadId: UUID) = finishedReceiveContexts.any { it.second == threadId }

    fun cleanUpFinishedReceiveContexts(cleanUpTimeoutMs: Long) {
        val now = System.currentTimeMillis()

        while (finishedReceiveContexts.isNotEmpty() && finishedReceiveContexts.peekLast().first + cleanUpTimeoutMs >= now)
            finishedReceiveContexts.removeLast()
    }
}
