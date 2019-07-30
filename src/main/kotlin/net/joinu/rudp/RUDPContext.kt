package net.joinu.rudp

import kotlinx.coroutines.CancellableContinuation
import net.joinu.wirehair.Wirehair
import sun.nio.ch.DirectBuffer
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.CancellationException


/**
 * Send context is responsible for block supply for each send thread.
 * It is created when input data retrieved from the send queue, and destroyed when the transmission of that data
 * completes or exit condition is met.
 *
 * @param threadId [UUID] - each thread is represents the process of the transmission of some input data
 * @param packet [QueuedDatagramPacket] - the same as [java.net.DatagramPacket] but with [ByteBuffer] for data
 * @param repairBlockSizeBytes [Int] - how much bytes there are in repair block without it's metadata
 * @param continuation [CancellableContinuation] of [RUDPSendContext] - continuation that completes when send succeeds,
 *  completes exceptionally when socket closed before send completes, and can be canceled (that will cancel sending)
 */
abstract class RUDPSendContext(
    val threadId: UUID,
    val packet: QueuedDatagramPacket,
    val repairBlockSizeBytes: Int,
    val continuation: CancellableContinuation<RUDPSendContext>
) {
    var blockId: Int = 0
    var lastGetRepairBlocksTimestamp: Long = 0
    var ackReceived: Boolean = false

    abstract fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int, mtuBytes: Int): List<RepairBlock>
    abstract fun destroy()

    fun isCongestionControlTimeoutElapsed(congestionControlTimeoutMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return lastGetRepairBlocksTimestamp + congestionControlTimeoutMs < now
    }
}

/**
 * This send context is using FEC encoded blocks for data transmission
 */
class EncodingRUDPSendContext(
    threadId: UUID,
    packet: QueuedDatagramPacket,
    repairBlockSizeBytes: Int,
    cancellableContinuation: CancellableContinuation<RUDPSendContext>
) : RUDPSendContext(threadId, packet, repairBlockSizeBytes, cancellableContinuation) {

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

    override fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int, mtuBytes: Int): List<RepairBlock> {
        val k = windowSizeBytes / mtuBytes
        val prevWindowBlockId = blockId

        val repairBlocks = mutableListOf<RepairBlock>()

        while (blockId <= prevWindowBlockId + k) {
            val repairBlockBuffer = ByteBuffer.allocateDirect(repairBlockSizeBytes)

            val writeLen = encoder.encode(blockId, repairBlockBuffer as DirectBuffer, repairBlockSizeBytes)

            
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

            blockId++
        }

        lastGetRepairBlocksTimestamp = System.currentTimeMillis()

        return repairBlocks
    }
}

/**
 * When our input data size is less than MTU there is no reason to encode anything - we can just send input data in a
 *  single block
 */
class DummyRUDPSendContext(
    threadId: UUID,
    packet: QueuedDatagramPacket,
    repairBlockSizeBytes: Int,
    cancellableContinuation: CancellableContinuation<RUDPSendContext>
) : RUDPSendContext(threadId, packet, repairBlockSizeBytes, cancellableContinuation) {

    override fun destroy() {}

    override fun getNextWindowSizeRepairBlocks(windowSizeBytes: Int, mtuBytes: Int): List<RepairBlock> {
        val k = 3
        val prevWindowBlockId = blockId

        val repairBlocks = mutableListOf<RepairBlock>()

        while (blockId <= prevWindowBlockId + k) {

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

            blockId++
        }

        lastGetRepairBlocksTimestamp = System.currentTimeMillis()

        return repairBlocks
    }
}

/**
 * Receive context is responsible for processing of the ingoing data packets.
 * It is created when we receive a block of unknown [threadId] and destroyed when transmission is complete or after
 * inactivity for some period of time [cleanUpTimeoutMs].
 *
 * @param threadId [UUID] - the id of the transmission thread
 */
abstract class RUDPReceiveContext(val threadId: UUID) {
    abstract fun tryToRecoverFrom(block: RepairBlock): ByteBuffer?
    abstract fun destroy()
}

/**
 * This receive context is using FEC decoding to gather original data.
 */
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

/**
 * This context is used when data is to small to be FEC'ed.
 */
class DummyRUDPReceiveContext(threadId: UUID) : RUDPReceiveContext(threadId) {

    override fun destroy() {}

    override fun tryToRecoverFrom(block: RepairBlock): ByteBuffer? {
        return block.data
    }
}

/**
 * Manager class for all contexts within a single socket. Can create, destroy and change state of contexts.
 */
class RUDPContextManager {
    private val sendContexts = mutableMapOf<UUID, RUDPSendContext>()
    private val receiveContexts = mutableMapOf<UUID, Pair<Long, RUDPReceiveContext>>()
    private val finishedReceiveContexts = LinkedList<Pair<Long, UUID>>()

    fun createOrGetSendContext(
        threadId: UUID,
        packet: QueuedDatagramPacket,
        repairBlockSizeBytes: Int,
        cancellableContinuation: CancellableContinuation<RUDPSendContext>
    ) =
        sendContexts.getOrPut(threadId) {
            if (packet.data.limit() > repairBlockSizeBytes)
                EncodingRUDPSendContext(threadId, packet, repairBlockSizeBytes, cancellableContinuation)
            else
                DummyRUDPSendContext(threadId, packet, repairBlockSizeBytes, cancellableContinuation)
        }

    fun destroySendContext(threadId: UUID): RUDPSendContext? {
        sendContexts[threadId]?.destroy()
        return sendContexts.remove(threadId)
    }

    fun sendContextExists(threadId: UUID) = sendContexts.containsKey(threadId)

    fun getSendContext(threadId: UUID) = sendContexts[threadId]

    fun getAllSendContexts() = sendContexts.values

    fun destroyAllCanceledSendContexts() {
        val ids = sendContexts.values.filter { it.continuation.isCancelled }.map { it.threadId }
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
        val ids = receiveContexts.entries.filter { it.value.first + cleanUpTimeoutMs < now }.map { it.key }

        ids.forEach { destroyReceiveContext(it) }
    }

    /**
     * Called before socket is closed
     */
    fun destroyAllContexts() {
        val receiveThreadIds = receiveContexts.keys
        receiveThreadIds.forEach { destroyReceiveContext(it) }

        val sendThreadIds = sendContexts.keys
        sendThreadIds.forEach {
            val context = destroySendContext(it) ?: return@forEach

            context.continuation.cancel(CancellationException("Socket was closed"))
        }
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

        while (finishedReceiveContexts.isNotEmpty() && finishedReceiveContexts.peekLast().first + cleanUpTimeoutMs < now)
            finishedReceiveContexts.removeLast()
    }
}
