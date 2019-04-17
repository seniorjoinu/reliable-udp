package net.joinu.nioudp

import mu.KotlinLogging
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.*


interface NioSocket : Closeable {
    fun bind(address: InetSocketAddress)
    fun listen()
    fun send(data: ByteBuffer, to: InetSocketAddress)
    fun onMessage(handler: NetworkMessageHandler)
    fun getSocketState(): SocketState
}

open class NonBlockingUDPSocket(val chunkSizeBytes: Int = RECOMMENDED_CHUNK_SIZE_BYTES) : NioSocket {

    private val logger = KotlinLogging.logger("NonBlockingUDPSocket-${Random().nextInt()}")

    init {
        require(chunkSizeBytes <= MAX_CHUNK_SIZE_BYTES) {
            "Maximum chunk size limit reached (provided: $chunkSizeBytes, limit: $MAX_CHUNK_SIZE_BYTES)"
        }
    }

    val actualChunkSizeBytes = chunkSizeBytes + DATA_SIZE_BYTES

    // TODO: add lock to channel
    lateinit var channel: DatagramChannel
    var onMessageHandler: NetworkMessageHandler? = null

    protected var state = SocketState.UNBOUND

    override fun getSocketState() = state
    fun isBound(): Boolean = state == SocketState.BOUND
    fun isClosed(): Boolean = state == SocketState.CLOSED

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler

        logger.trace { "onMessage handler set" }
    }

    override fun bind(address: InetSocketAddress) {
        channel = DatagramChannel.open()
        channel.configureBlocking(false)
        channel.bind(address)

        state = SocketState.BOUND

        logger.trace { "Address $address bound" }
    }

    override fun close() {
        channel.close()

        state = SocketState.CLOSED

        logger.trace { "Socket closed" }
    }

    protected fun throwIfNotBound() {
        if (!isBound()) error("NonBlockingUDPSocket is not bound yet.")
    }

    protected fun throwIfClosed() {
        if (isClosed()) error("NonBlockingUDPSocket is already closed.")
    }

    override fun listen() {
        throwIfNotBound()
        throwIfClosed()

        val buf = ByteBuffer.allocateDirect(actualChunkSizeBytes)

        logger.trace { "Listening" }

        while (!isClosed()) {
            val remoteAddress = channel.receive(buf)

            if (buf.position() == 0) continue

            buf.flip()
            val size = buf.int
            val data = ByteBuffer.allocateDirect(size)
            buf.limit(size + Int.SIZE_BYTES)
            data.put(buf)
            data.flip()

            buf.clear()

            val from = InetSocketAddress::class.java.cast(remoteAddress)

            logger.trace { "Received data packet from $from, invoking onMessage handler" }

            onMessageHandler?.invoke(data, from)
        }
    }

    override fun send(data: ByteBuffer, to: InetSocketAddress) {
        throwIfNotBound()
        throwIfClosed()

        require(data.limit() <= chunkSizeBytes) { "Size of data should be LEQ than $chunkSizeBytes bytes" }

        val wrappedData = ByteBuffer.allocateDirect(actualChunkSizeBytes)

        wrappedData.putInt(data.limit())
        wrappedData.put(data)
        wrappedData.flip()

        logger.trace { "Sending $wrappedData to $to" }

        channel.send(wrappedData, to)
    }
}
