package net.joinu.rudp

import mu.KotlinLogging
import net.joinu.wirehair.Wirehair
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


class RUDPSocket(mtuBytes: Int, private val cleanUpTimeoutMs: Long = 1000 * 60 * 10) {
    companion object {
        var count = 0

        init {
            Wirehair.init()
        }
    }

    private val logger = KotlinLogging.logger("RUDPSocket-${++count}")

    /* --- High-level RUDP stuff --- */

    val sendQueue =
        ConcurrentLinkedQueue<Triple<QueuedDatagramPacket, RUDPSendContext.() -> Boolean, RUDPSendContext.() -> Unit>>()
    val receiveQueue = ConcurrentLinkedQueue<QueuedDatagramPacket>()
    val contextManager = RUDPContextManager()
    val repairBlockSizeBytes = mtuBytes - RepairBlock.METADATA_SIZE_BYTES

    /**
     * Adds data in processing queue for send.
     *
     * @param data [ByteBuffer] - normalized (flipped) data
     * @param to [InetSocketAddress] - address to send data to
     * @param stop lambda returning [Boolean] - called on each processing loop iteration, if returns true - sending is canceled
     * @param complete lambda returning [Void] - called when send is completed successfully
     */
    fun send(
        data: ByteBuffer,
        to: InetSocketAddress,
        stop: RUDPSendContext.() -> Boolean = { false },
        complete: RUDPSendContext.() -> Unit = {}
    ) {
        sendQueue.add(Triple(QueuedDatagramPacket(data, to), stop, complete))
    }

    /**
     * Tries to retrieve some data from receive queue.
     *
     * @return optional [QueuedDatagramPacket] - if there is a data returns packet, otherwise - null
     */
    fun receive() = receiveQueue.poll()

    /**
     * Runs processing loop once.
     *
     * Loop consists of three stages:
     *  1. Clean up
     *  2. Processing send
     *  3. Processing receive
     */
    fun runOnce() {
        // clean up
        contextManager.cleanUpFinishedReceiveContexts(cleanUpTimeoutMs)
        contextManager.destroyAllForbiddenReceiveContexts(cleanUpTimeoutMs)
        contextManager.destroyAllExitedSendContexts()

        // send
        prepareSend()
        processSend()

        // receive
        val blocks = prepareReceive()
        processReceive(blocks)
    }

    private fun processSend() {
        val contexts = contextManager.getAllSendContexts()

        contexts.asSequence()
            .filter { it.isCongestionControlTimeoutElapsed(300) } // TODO: get from CC
            .map { it.getNextWindowSizeRepairBlocks(repairBlockSizeBytes * 2) to it.packet.address } // TODO: get from CC
            .forEach { (blocks, to) ->
                blocks.forEach { block ->
                    logger.trace { "Sending REPAIR_BLOCK threadId: ${block.threadId} blockId: ${block.blockId}" }
                    block.serialize(sendBuffer)
                    write(sendBuffer, to)
                }
            }
    }

    private fun prepareSend() {
        val sendQueueCopy =
            mutableListOf<Triple<QueuedDatagramPacket, RUDPSendContext.() -> Boolean, RUDPSendContext.() -> Unit>>()

        while (true) {
            val packet = sendQueue.poll()
                ?: break

            sendQueueCopy.add(packet)
        }

        sendQueueCopy.forEach { (packet, exit, complete) ->
            val threadId = UUID.randomUUID()

            logger.trace { "Transmission for threadId: $threadId is started" }

            contextManager.createOrGetSendContext(
                threadId,
                packet,
                repairBlockSizeBytes,
                exit,
                complete
            )
        }
    }

    private fun prepareReceive(): List<Pair<RepairBlock, InetSocketAddress>> {
        val packets = mutableListOf<QueuedDatagramPacket>()
        while (true) {
            val packet = read()
                ?: break

            packets.add(packet)
        }

        return packets.mapNotNull { packet ->
            when (parseFlag(packet.data)) {
                Flags.ACK -> {
                    val ack = parseAck(packet.data)

                    if (!contextManager.sendContextExists(ack.threadId))
                        return@mapNotNull null

                    val context = contextManager.getSendContext(ack.threadId)!!

                    context.ackReceived = true

                    logger.trace { "Received ACK for threadId: ${ack.threadId}, stopping..." }

                    context.complete(context)
                    contextManager.destroySendContext(context.threadId)

                    null
                }
                Flags.BLOCK_ACK -> {
                    val blockAck = parseBlockAck(packet.data)

                    logger.trace { "Received BLOCK_ACK message for threadId: ${blockAck.threadId} from: ${packet.address}" }

                    /* TODO: handle congestion control tune */

                    null
                }
                Flags.REPAIR -> {
                    val block = parseRepairBlock(packet.data)

                    logger.trace { "Received REPAIR_BLOCK message for threadId: ${block.threadId} blockId: ${block.blockId} from: ${packet.address}" }

                    block to packet.address
                }
                else -> null
            }
        }
    }

    private fun processReceive(blocks: List<Pair<RepairBlock, InetSocketAddress>>) {
        blocks.forEach { (block, from) ->
            val threadId = block.threadId

            val context = if (contextManager.isReceiveContextFinished(block.threadId)) {
                logger.trace { "Received a repair block for already received threadId: ${block.threadId}, skipping..." }
                sendAck(threadId, from)
                return@forEach
            } else {
                contextManager.createOrGetReceiveContext(threadId, block.messageSizeBytes, block.blockSizeBytes)
            }

            contextManager.updateReceiveContext(threadId)

            sendBlockAck(threadId, block.blockId, from)

            val message = context.tryToRecoverFrom(block) ?: return@forEach

            contextManager.markReceiveContextAsFinished(threadId)
            contextManager.destroyReceiveContext(threadId)

            sendAck(threadId, from)

            receiveQueue.add(QueuedDatagramPacket(message, from))
        }
    }

    private fun sendAck(threadId: UUID, to: InetSocketAddress) {
        logger.trace { "Sending ACK for threadId: $threadId to $to" }

        val ack = Ack(threadId, 0F) // TODO: get from congestion index

        ack.serialize(sendBuffer)

        write(sendBuffer, to)
    }

    private fun sendBlockAck(threadId: UUID, blockId: Int, to: InetSocketAddress) {
        logger.trace { "Sending BLOCK_ACK for threadId: $threadId to $to" }

        val blockAck = BlockAck(threadId, blockId, 0F) // TODO: get from congestion index

        blockAck.serialize(sendBuffer)

        write(sendBuffer, to)
    }

    private fun parseRepairBlock(buffer: ByteBuffer) = RepairBlock.deserialize(buffer)
    private fun parseAck(buffer: ByteBuffer) = Ack.deserialize(buffer)
    private fun parseBlockAck(buffer: ByteBuffer) = BlockAck.deserialize(buffer)
    private fun parseFlag(buffer: ByteBuffer) = buffer.get()

    /* --- Low-level plain UDP stuff --- */

    private var channel = DatagramChannel.open()
    private val sendBuffer = ByteBuffer.allocateDirect(mtuBytes)
    private val receiveBuffer = ByteBuffer.allocateDirect(mtuBytes)
    private var state = SocketState.UNBOUND

    init {
        channel.configureBlocking(false)
        channel.setOption(StandardSocketOptions.SO_SNDBUF, Int.MAX_VALUE)
        channel.setOption(StandardSocketOptions.SO_RCVBUF, Int.MAX_VALUE)
    }

    /**
     * Binds to the local address. Before this call you're unable to receive packets.
     *
     * @param on [InetSocketAddress] - address to bind
     */
    fun bind(on: InetSocketAddress) = synchronized(state) {
        throwIfClosed()
        channel.bind(on)
        state = SocketState.BOUND
    }

    /**
     * Closes this socket - after this you should create another one to work with
     */
    fun close() = synchronized(state) {
        channel.close()
        state = SocketState.CLOSED
    }

    /**
     * Get socket state
     *
     * @return [SocketState]
     */
    fun getState() = state

    /**
     * Is socket closed
     *
     * @return [Boolean]
     */
    fun isClosed() = state == SocketState.CLOSED

    private fun write(data: ByteBuffer, address: InetSocketAddress) = synchronized(state) {
        logger.trace { "Sending ${data.limit()} bytes to $address" }
        throwIfClosed()
        channel.send(data, address)

        data.clear()
    }

    private fun read(): QueuedDatagramPacket? = synchronized(state) {
        throwIfNotBound()
        val remoteAddress = channel.receive(receiveBuffer)

        if (receiveBuffer.position() == 0) return null

        val size = receiveBuffer.position()

        receiveBuffer.flip()

        logger.trace { "Receiving $size bytes from $remoteAddress" }

        val data = ByteBuffer.allocate(size)

        data.put(receiveBuffer)
        data.flip()

        receiveBuffer.clear()

        val from = InetSocketAddress::class.java.cast(remoteAddress)

        QueuedDatagramPacket(data, from)
    }

    private fun throwIfNotBound() = check(state == SocketState.BOUND) { "Socket should be BOUND" }
    private fun throwIfClosed() = check(state != SocketState.CLOSED) { "Socket is closed" }
}
