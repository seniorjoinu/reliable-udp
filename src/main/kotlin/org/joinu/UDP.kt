package org.joinu

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.lang.IllegalArgumentException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException


typealias NetworkMessageHandler = (bytes: ByteArray, from: InetSocketAddress) -> Unit

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
