package net.joinu.nioudp

import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.*


const val ALLOCATION_THRESHOLD_BYTES = 1400

class AsyncUDPSocket {

    private val logger = KotlinLogging.logger("AsyncUDPSocket-${Random().nextInt()}")

    lateinit var channel: DatagramChannel
    private val channelMutex = Mutex()

    var onMessageHandler: NetworkMessageHandler? = null

    private var state = SocketState.UNBOUND

    fun getSocketState() = state
    fun isClosed(): Boolean = state == SocketState.CLOSED

    fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler

        logger.trace { "onMessage handler set" }
    }

    suspend fun bind(address: InetSocketAddress) {
        channelMutex.withLock {
            throwIfNotUnbound()

            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.bind(address)

            state = SocketState.BOUND

            logger.trace { "Address $address bound" }
        }
    }

    suspend fun close() {
        channelMutex.withLock {
            throwIfClosed()
            throwIfUnbound()

            channel.close()
            onMessageHandler = null

            state = SocketState.CLOSED

            logger.trace { "Socket closed" }
        }
    }

    private fun throwIfClosed() {
        if (isClosed()) error("AsyncUDPSocket is already closed.")
    }

    private fun throwIfNotUnbound() {
        if (getSocketState() != SocketState.UNBOUND) error("AsyncUDPSocket is not UNBOUND")
    }

    private fun throwIfUnbound() {
        if (getSocketState() == SocketState.UNBOUND) error("AsyncUDPSocket is UNBOUND")
    }

    suspend fun listen() {
        throwIfClosed()

        val buf = ByteBuffer.allocateDirect(MAX_CHUNK_SIZE_BYTES)

        logger.trace { "Listening" }
        state = SocketState.LISTENING

        supervisorScope {
            while (!isClosed()) {
                val remoteAddress = channelMutex.withLock {
                    if (channel.isOpen) channel.receive(buf)
                    else null
                }

                if (buf.position() == 0) continue

                val size = buf.position()

                buf.flip()

                // when allocating byte buffer follow the next rule: if data.size < ~1400 bytes - use on heap buffer, else - use off heap buffer. Why? Because it's faster.
                val data = if (size < ALLOCATION_THRESHOLD_BYTES)
                    ByteBuffer.allocate(size)
                else
                    ByteBuffer.allocateDirect(size)

                data.put(buf)
                data.flip()

                buf.clear()

                launch {
                    val from = InetSocketAddress::class.java.cast(remoteAddress)

                    logger.trace { "Received data packet from $from, invoking onMessage handler" }

                    onMessageHandler?.invoke(data, from)
                }
            }
        }
    }

    suspend fun send(data: ByteBuffer, to: InetSocketAddress) {
        throwIfClosed()

        require(data.limit() <= MAX_CHUNK_SIZE_BYTES) { "Size of data should be LEQ than $MAX_CHUNK_SIZE_BYTES bytes" }

        channelMutex.withLock {
            logger.trace { "Sending $data to $to" }

            channel.send(data, to)
        }
    }
}
