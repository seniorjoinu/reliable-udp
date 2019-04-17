package net.joinu.nioudp

import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel


interface NioSocket : Closeable {
    fun bind(address: InetSocketAddress)
    fun listen()
    fun send(data: ByteArray, to: InetSocketAddress)
    fun onMessage(handler: NetworkMessageHandler)
    fun getSocketState(): SocketState
}

open class NonBlockingUDPSocket(val chunkSizeBytes: Int = RECOMMENDED_CHUNK_SIZE_BYTES) : NioSocket {

    val actualChunkSizeBytes = chunkSizeBytes + DATA_SIZE_BYTES

    init {
        require(chunkSizeBytes <= MAX_CHUNK_SIZE_BYTES) {
            "Maximum chunk size limit reached (provided: $chunkSizeBytes, limit: $MAX_CHUNK_SIZE_BYTES)"
        }
    }

    lateinit var channel: DatagramChannel
    var onMessageHandler: NetworkMessageHandler? = null

    protected var state = SocketState.UNBOUND

    override fun getSocketState() = state
    fun isBound(): Boolean = state == SocketState.BOUND
    fun isClosed(): Boolean = state == SocketState.CLOSED

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler
    }

    override fun bind(address: InetSocketAddress) {
        channel = DatagramChannel.open()
        channel.configureBlocking(false)
        channel.bind(address)

        state = SocketState.BOUND
    }

    override fun close() {
        channel.close()

        state = SocketState.CLOSED
    }

    protected fun throwIfNotBound() {
        if (!isBound())
            throw IllegalStateException("NonBlockingUDPSocket is not bound yet.")
    }

    protected fun throwIfClosed() {
        if (isClosed())
            throw IllegalStateException("NonBlockingUDPSocket is already closed.")
    }

    override fun listen() {
        throwIfNotBound()
        throwIfClosed()

        val buf = ByteBuffer.allocateDirect(actualChunkSizeBytes)

        while (!isClosed()) {
            val remoteAddress = channel.receive(buf)

            if (buf.position() == 0) continue

            buf.flip()
            val size = buf.int
            val data = ByteArray(size)
            buf.get(data)
            buf.clear()

            onMessageHandler?.invoke(data, InetSocketAddress::class.java.cast(remoteAddress))
        }
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        throwIfNotBound()
        throwIfClosed()

        require(data.size <= chunkSizeBytes) { "Size of data should be LEQ than $chunkSizeBytes bytes" }

        val wrappedData = ByteBuffer.allocateDirect(actualChunkSizeBytes)
            .putInt(data.size)
            .put(data)
        wrappedData.position(0)

        channel.send(wrappedData, to)
    }
}
