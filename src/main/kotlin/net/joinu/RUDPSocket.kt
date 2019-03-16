package net.joinu

import net.joinu.nioudp.NetworkMessageHandler
import net.joinu.nioudp.NioSocket
import net.joinu.nioudp.SocketState
import java.net.InetSocketAddress

/**
 * Data piece structure: {
 *  data: ByteArray,
 *  threadId: Long,
 *  sequenceIdx: Int,
 *  sequenceSize: Int
 * }
 *
 * Flow:
 *  1. Connection initialization (or usage of the already created one)
 *  2. Sending-receiving
 *  3. Connection garbage collection
 *
 * Congestion Measurement Algorithm (CMA):
 *  Each node has it's local Congestion Index 0-255 where 0 means very low network congestion (e.g.
 *  local network) and 255 means very high network congestion (e.g. wireless lossy ad-hoc network). Values [0-55] mean
 *  very good, values [56-200] mean mediocre, values [200-255] mean very bad. Congestion Index of node is average median
 *  (distribution maximum) of local Congestion Indexes of node's neighbours. Node calculates local Congestion Indexes
 *  of it's neighbours using next values: neighbour's average Congestion Index, latency between the node and it's neighbour
 *  and retransmission rate. By default (when the node has no neighbours) it considers itself in a bad network and randomly
 *  chooses Congestion Index from range [200-255].
 *  When local Congestion Index is calculated it can be used to optimize retransmission timeouts and retransmission window
 *  size.
 *
 * TODO: delegate crypto-stuff to end user
 *
 * Connection initialization:
 *  1. [A] send initial data from CMA + (first data piece + pubkey + signature) if needed
 *  2. [B] send initial data from CMA + (ACK + pubkey + signature) if needed
 *  3. [A] send adjusted data from CMA + (second data piece + signature) if needed
 *  4. [B] send adjusted data from CMA + (ACK + signature) if needed
 *  TODO: maybe it is a global pattern?
 *
 * Sending:
 *  0. sign data if needed
 *  1. inflate data if needed
 *  2. split data if needed
 *  3. fec data if needed
 *  4. add index and max index to data
 *  5. calculate timeouts and window size
 *  6. send each piece of data to address
 *  7. wait til timeout or til first ACK package received
 *  8. retransmit missing
 *  9. repeat 7-8 til transmission of all data reached
 *
 * Receiving:
 *  1. receive at least one piece of data
 *  2. wait til timeout or til all pieces received
 *  3. send ACK
 *  4. repeat 2-3 til transmission of all data reached
 *  5. unfec data if needed
 *  6. merge data if needed
 *  7. deflate data if needed
 *  8. check signature if needed
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