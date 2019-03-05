package org.joinu

import java.io.Closeable
import java.net.*


/**
 * Replacement for [DatagramSocket]
 */
interface MinUDPSocket : Closeable {
    /**
     * Tries to read data once (so non-blocking)
     */
    fun peek(): DatagramPacket?

    /**
     * Tries to send data
     */
    fun send(packet: DatagramPacket)
}


class UDPSocket(val bindAdress: InetSocketAddress = InetSocketAddress(0)) : MinUDPSocket {

    private var closed = false
    private var bound = false

    init {
        bind(bindAdress)
    }

    override fun peek(): DatagramPacket? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun send(packet: DatagramPacket) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private fun bind(address: InetSocketAddress) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun close() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}