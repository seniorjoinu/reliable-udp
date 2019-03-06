package org.joinu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.coroutines.CoroutineContext


typealias NetworkMessageHandler = (bytes: ByteArray, from: InetSocketAddress) -> Unit
typealias AsyncNetworkMessageHandler = suspend (bytes: ByteArray, from: InetSocketAddress) -> Unit


class NetworkProvider(
    val channel: DatagramChannel,
    val chunkSizeBytes: Int,
    override val coroutineContext: CoroutineContext = Dispatchers.IO
) : CoroutineScope {

    companion object {
        fun provideChannel(address: InetSocketAddress = InetSocketAddress(0)): DatagramChannel {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.bind(address)

            return channel
        }

        fun provide(address: InetSocketAddress = InetSocketAddress(0), chunkSizeBytes: Int = 508)
                = NetworkProvider(provideChannel(address), chunkSizeBytes)
    }

    private val onMessageHandlers = mutableListOf<AsyncNetworkMessageHandler>()

    fun addOnMessageHandler(handler: AsyncNetworkMessageHandler) = onMessageHandlers.add(handler)

    fun listen(isActive: () -> Boolean = { true }) {
        val buf = ByteBuffer.allocateDirect(chunkSizeBytes)

        while (isActive()) {
            val remoteAddress = channel.receive(buf)

            if (buf.position() == 0) continue

            buf.flip()
            val data = ByteArray(buf.limit())
            buf.get(data)
            buf.clear()

            onMessageHandlers.forEach {
                launch(Dispatchers.Default) {
                    it(data, InetSocketAddress::class.java.cast(remoteAddress))
                }
            }
        }
    }

    fun send(data: ByteArray, to: InetSocketAddress) {
        val paddedData = applyPadding(data)

        channel.send(ByteBuffer.wrap(paddedData), to)
    }

    fun listenAsync(isActive: () -> Boolean = { true }) = launch { listen(isActive) }
    fun sendAsync(data: ByteArray, to: InetSocketAddress) = launch { send(data, to) }

    private fun applyPadding(to: ByteArray): ByteArray {
        require(to.size <= chunkSizeBytes) { "Size of data should be LEQ than $chunkSizeBytes bytes" }

        return if (to.size < chunkSizeBytes)
            ByteArray(chunkSizeBytes) { if (it < to.size) to[it] else 0 }
        else
            to
    }
}


object UDP {
    val onMessageHandlers: MutableMap<InetSocketAddress, MutableList<NetworkMessageHandler>> =
        mutableMapOf()


    fun listen(socket: DatagramSocket, maxChunkSizeBytes: Int = 500, isActive: () -> Boolean = { true }) {
        val on = InetSocketAddress(socket.localAddress, socket.localPort)
        val buf = ByteArray(maxChunkSizeBytes)

        while (isActive() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf.clone(), buf.size)
                socket.receive(packet)

                if (onMessageHandlers.containsKey(on))
                    onMessageHandlers[on]!!.forEach {
                        it(packet.data, InetSocketAddress(packet.address, packet.port))
                    }
            } catch (e: SocketException) {
                println("ERROR: socket closed by user")
            }
        }
    }

    @Throws(IllegalArgumentException::class)
    fun send(data: ByteArray, to: InetSocketAddress, maxChunkSizeBytes: Int = 500) {
        require(data.size <= maxChunkSizeBytes) { "Size of data should be LEQ than $maxChunkSizeBytes bytes" }

        DatagramSocket().send(DatagramPacket(data, data.size, to))
    }

    fun onMessage(to: InetSocketAddress, handler: NetworkMessageHandler) {
        if (!onMessageHandlers.containsKey(to))
            onMessageHandlers[to] = mutableListOf()

        onMessageHandlers[to]!!.add(handler)
    }
}
