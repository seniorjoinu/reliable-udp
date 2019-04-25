package net.joinu.nioudp

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.*
import java.util.concurrent.Executors


interface NioSocket {
    suspend fun bind(address: InetSocketAddress)
    suspend fun listen()
    suspend fun send(data: ByteBuffer, to: InetSocketAddress)
    fun onMessage(handler: NetworkMessageHandler)
    fun getSocketState(): SocketState
    suspend fun close()
}

const val ALLOCATION_THRESHOLD_BYTES = 1400

class NonBlockingUDPSocket : NioSocket {

    private val logger = KotlinLogging.logger("NonBlockingUDPSocket-${Random().nextInt()}")

    lateinit var channel: DatagramChannel
    private val channelMutex = Mutex()

    private val listenDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    var onMessageHandler: NetworkMessageHandler? = null

    private var state = SocketState.UNBOUND

    override fun getSocketState() = state
    fun isClosed(): Boolean = state == SocketState.CLOSED

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler

        logger.trace { "onMessage handler set" }
    }

    override suspend fun bind(address: InetSocketAddress) {
        channelMutex.withLock {
            throwIfNotUnbound()

            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.bind(address)

            state = SocketState.BOUND

            logger.trace { "Address $address bound" }
        }
    }

    override suspend fun close() {
        channelMutex.withLock {
            throwIfClosed()
            throwIfUnbound()

            channel.close()

            state = SocketState.CLOSED

            logger.trace { "Socket closed" }
        }
    }

    private fun throwIfClosed() {
        if (isClosed()) error("NonBlockingUDPSocket is already closed.")
    }

    private fun throwIfNotUnbound() {
        if (getSocketState() != SocketState.UNBOUND) error("NonBlockingUDPSocket is not UNBOUND")
    }

    private fun throwIfUnbound() {
        if (getSocketState() == SocketState.UNBOUND) error("NonBlockingUDPSocket is UNBOUND")
    }

    override suspend fun listen() {
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

                // when allocating byte buffer follow the next rule: if data.size < ~1400 bytes - use on heap buffer, else - use off heap buffer
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

    override suspend fun send(data: ByteBuffer, to: InetSocketAddress) {
        throwIfClosed()

        require(data.limit() <= MAX_CHUNK_SIZE_BYTES) { "Size of data should be LEQ than $MAX_CHUNK_SIZE_BYTES bytes" }

        channelMutex.withLock {
            logger.trace { "Sending $data to $to" }

            channel.send(data, to)
        }
    }
}
