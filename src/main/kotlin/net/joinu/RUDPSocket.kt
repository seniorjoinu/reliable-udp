package net.joinu

import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NioSocket
import net.joinu.nioudp.SocketState
import java.net.InetSocketAddress

/**
 * Sending:
 *  0. init connection
 *  1. split data
 *  2. fec data
 *  3. add index and max index to data
 *  4. calculate timeouts and window size
 *  5. send each piece of data to address (measure time passed til this process - STO)
 *  6. wait til timeout or til first ACK package received
 *  6.1     if ACK received - wait til STO then retransmit
 *  6.2     if timeout passed - retransmit
 */

class RUDPSocket : NioSocket {
    companion object {
        init {
            init()
        }

        @JvmStatic()
        fun init() {
            //SerializationUtils.registerClass()
        }
    }

    override fun listen() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun addOnMessageHandler(handler: NetworkMessageHandler) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getSocketState(): SocketState {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun close() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun bind(address: InetSocketAddress) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}