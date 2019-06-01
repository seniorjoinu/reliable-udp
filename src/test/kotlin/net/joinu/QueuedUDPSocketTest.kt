package net.joinu

import net.joinu.nioudp.QueuedUDPSocket
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class QueuedUDPSocketTest {
    @Test
    fun `single send and receive works fine`() {
        val bufSize = 200
        val socket1 = QueuedUDPSocket(bufSize)
        val socket2 = QueuedUDPSocket(bufSize)

        val addr1 = InetSocketAddress("localhost", 1337)
        val addr2 = InetSocketAddress("localhost", 1338)

        socket1.listen(addr1)
        socket2.listen(addr2)

        val data1 = ByteArray(199) { it.toByte() }
        val data2 = ByteArray(100) { it.toByte() }

        socket1.send(data1, addr2)
        socket2.send(data2, addr1)

        val (data2Received, addr2Received) = socket1.receiveBlocking()!!.toPairByteArray()
        val (data1Received, addr1Received) = socket2.receiveBlocking()!!.toPairByteArray()

        socket1.close()
        socket2.close()

        assert(data1.contentEquals(data1Received)) { "data1 is not equal" }
        assert(data2.contentEquals(data2Received)) { "data2 is not equal" }

        println(data1Received.contentToString())
        println(data2Received.contentToString())
    }
}