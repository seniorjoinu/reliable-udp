package net.joinu.rudp

import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NioSocket
import net.joinu.nioudp.SocketState
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Data piece structure: {
 *  data: ByteArray,
 *  threadId: Long,
 *  sequenceIdx: Int,
 *  sequenceSize: Int
 * }
 */

class RUDPSocket : NioSocket {
    companion object {
        init {
            //SerializationUtils.registerClass()
        }
    }

    val connections = ConcurrentHashMap<InetSocketAddress, RUDPConnection>()

    override fun listen() {
        // wait for new message
        // if there is no connection with this address - create
        // if it is ACK - cancel send coroutine
        // if it is a repair block - accumulate it
        // when all needed repair blocks received:
        //  send ACK
        //  unfec
        //  clean up
        //  call message handlers
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        // get existing connection or create new
        // start send coroutine and add it's handle to map
        // fec
        // add metadata to each repair block
        // send WINDOW SIZE of repair blocks
        // wait FLOOD CONTROL TIMEOUT (FCT) or TRANSMISSION TIMEOUT (TRT)
        // if FCT - send another WINDOW SIZE of repair blocks
        // if TRT - throw
        // update congestion index
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