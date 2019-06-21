package net.joinu

import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.send
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class ExampleTest {
    @Test
    fun `everythin is ok`() {
        // create a pair of sockets
        val rudp1 = RUDPSocket()
        val rudp2 = RUDPSocket()

        val net1Addr = InetSocketAddress(1337)
        val net2Addr = InetSocketAddress(1338)

        // bind to some address
        rudp1.bind(net1Addr)
        rudp2.bind(net2Addr)

        // send some content
        var sent = 0

        val net1Content = ByteArray(20000) { it.toByte() }
        rudp1.send(net1Content, net2Addr) { sent++ } // non-blocking code, provides callbacks, adds data to send queue
        rudp1.send(
            net1Content,
            net2Addr
        ) { sent++ } // send second time to show multiplexing, the logic inside is done in sequence, so it is absolutely save to increment asynchronously

        var received = 0

        // there is no blocking "listen" or "run" - you can block your threads in a way you like
        while (received < 2 && sent < 2) {
            rudp1.runOnce() // processes data if it available
            rudp2.runOnce()

            // try to get data from receive queue
            val data = rudp2.receive()
            if (data != null) { // if there is data - increment, if there is no - try again
                received++
                assert(data.data.toByteArray().contentEquals(net1Content)) { "Content is not equal" }
            }
        }

        println("Data transmitted")

        // close sockets, free resources
        rudp1.close()
        rudp2.close()
    }
}
