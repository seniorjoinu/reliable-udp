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
            init()
        }

        @JvmStatic()
        fun init() {
            //SerializationUtils.registerClass()
        }

        // TODO: locally it will clash - it's bad for tests, but nice for production
        @JvmStatic()
        val connections = ConcurrentHashMap<InetSocketAddress, RUDPConnection>()
        @JvmStatic()
        val acks = ConcurrentHashMap<Long, MutableList<Int>>()
        @JvmStatic()
        val segments = ConcurrentHashMap<Long, MutableList<ByteArray>>()
    }

    override fun listen() {
        // wait for new message
        // if there is no connection with this address - create
        // if it is ACK add it to ACK collection
        // if it is data add it to data collection
        // when all data pieces received:
        //  remove metadata
        //  unfec
        //  merge
        //  deflate
        //  clean up ACK and data collections
        //  call message handlers
    }

    override fun send(data: ByteArray, to: InetSocketAddress) {
        // get existing connection or create new
        // inflate
        // split
        // fec
        // add metadata
        // push all segments to stack (backwards)
        // send WINDOW SIZE of segments
        // pop these segments from stack
        // wait for ACK till RTO or till all WINDOW SIZE ACK's received
        // update congestion index
        // if some ACKs are missing push these segments back to stack
        // repeat 5 previous steps until stack is empty
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