package org.joinu

import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel


open class NonBlockingUDPSocket(
    val chunkSizeBytes: Int = 508
) : Closeable {

    init {
        require(chunkSizeBytes <= MAX_CHUNK_SIZE_BYTES) {
            "Maximum chunk size limit reached (provided: $chunkSizeBytes, limit: $MAX_CHUNK_SIZE_BYTES)"
        }
    }

    lateinit var channel: DatagramChannel
    val onMessageHandlers = mutableListOf<NetworkMessageHandler>()

    protected var state = SocketState.UNBOUND

    fun getSocketState() = state
    fun isBound(): Boolean = state == SocketState.BOUND
    fun isClosed(): Boolean = state == SocketState.CLOSED

    fun addOnMessageHandler(handler: NetworkMessageHandler) = onMessageHandlers.add(handler)

    fun bind(address: InetSocketAddress) {
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

    fun listen() {
        throwIfNotBound()
        throwIfClosed()

        val buf = ByteBuffer.allocateDirect(chunkSizeBytes)

        while (!isClosed()) {
            val remoteAddress = channel.receive(buf)

            if (buf.position() == 0) continue

            buf.flip()
            val data = ByteArray(buf.limit())
            buf.get(data)
            buf.clear()

            onMessageHandlers.forEach {
                it(data, InetSocketAddress::class.java.cast(remoteAddress))
            }
        }
    }

    fun send(data: ByteArray, to: InetSocketAddress) {
        throwIfNotBound()
        throwIfClosed()

        val paddedData = applyPadding(data)

        channel.send(ByteBuffer.wrap(paddedData), to)
    }

    private fun applyPadding(to: ByteArray): ByteArray {
        require(to.size <= chunkSizeBytes) { "Size of data should be LEQ than $chunkSizeBytes bytes" }

        return if (to.size < chunkSizeBytes)
            ByteArray(chunkSizeBytes) { if (it < to.size) to[it] else 0 }
        else
            to
    }
}
