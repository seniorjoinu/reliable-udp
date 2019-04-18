package net.joinu.nioudp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.*
import kotlin.coroutines.CoroutineContext


interface NioSocket {
    suspend fun bind(address: InetSocketAddress)
    suspend fun listen()
    suspend fun send(data: ByteBuffer, to: InetSocketAddress)
    fun onMessage(handler: NetworkMessageHandler)
    fun getSocketState(): SocketState
    suspend fun close()
}

class NonBlockingUDPSocket(
    override val coroutineContext: CoroutineContext = Dispatchers.IO
) : NioSocket, CoroutineScope {

    private val logger = KotlinLogging.logger("NonBlockingUDPSocket-${Random().nextInt()}")

    lateinit var channel: DatagramChannel
    private val channelMutex = Mutex()

    var onMessageHandler: NetworkMessageHandler? = null

    private var state = SocketState.UNBOUND

    override fun getSocketState() = state
    fun isBound(): Boolean = state == SocketState.BOUND
    fun isClosed(): Boolean = state == SocketState.CLOSED

    override fun onMessage(handler: NetworkMessageHandler) {
        onMessageHandler = handler

        logger.trace { "onMessage handler set" }
    }

    override suspend fun bind(address: InetSocketAddress) {
        channelMutex.withLock {
            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.bind(address)

            state = SocketState.BOUND

            logger.trace { "Address $address bound" }
        }
    }

    override suspend fun close() {
        channelMutex.withLock {
            channel.close()

            state = SocketState.CLOSED

            logger.trace { "Socket closed" }
        }
    }

    private fun throwIfNotBound() {
        if (!isBound()) error("NonBlockingUDPSocket is not bound yet.")
    }

    private fun throwIfClosed() {
        if (isClosed()) error("NonBlockingUDPSocket is already closed.")
    }

    override suspend fun listen() {
        throwIfNotBound()
        throwIfClosed()

        val buf = ByteBuffer.allocateDirect(MAX_CHUNK_SIZE_BYTES)

        logger.trace { "Listening" }

        supervisorScope {
            while (!isClosed()) {
                val remoteAddress = channelMutex.withLock {
                    if (channel.isOpen) channel.receive(buf)
                    else null
                }

                if (buf.position() == 0) continue
                val size = buf.position()

                buf.flip()

                val data = ByteBuffer.allocateDirect(size)
                data.put(buf)
                data.flip()

                buf.clear()

                val from = InetSocketAddress::class.java.cast(remoteAddress)

                logger.trace { "Received data packet from $from, invoking onMessage handler" }

                launch(Dispatchers.Default) {
                    onMessageHandler?.invoke(data, from)
                }
            }
        }
    }

    override suspend fun send(data: ByteBuffer, to: InetSocketAddress) {
        throwIfNotBound()
        throwIfClosed()

        require(data.limit() <= MAX_CHUNK_SIZE_BYTES) { "Size of data should be LEQ than $MAX_CHUNK_SIZE_BYTES bytes" }

        channelMutex.withLock {
            logger.trace { "Sending $data to $to" }

            channel.send(data, to)
        }
    }
}
