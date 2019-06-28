package net.joinu

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.runSuspending
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

        val net1Content = ByteArray(20000) { it.toByte() }

        // running coroutines in some scope
        runBlocking {
            // start sockets in suspending mode
            launch { rudp1.runSuspending() }
            launch { rudp2.runSuspending() }

            // send-receive some stuff
            coroutineScope {
                launch { rudp1.send(net1Content, net2Addr) }.invokeOnCompletion { println("Data sent (1)") }
                launch { rudp1.send(net1Content, net2Addr) }.invokeOnCompletion { println("Data sent (2)") }
                launch { rudp2.receive() }.invokeOnCompletion { println("Data received (1)") }
                launch { rudp2.receive() }.invokeOnCompletion { println("Data received (2)") }
            }

            // stop sockets after all
            coroutineContext.cancelChildren()
        }

        println("Data transmitted")

        // close sockets, free resources
        rudp1.close()
        rudp2.close()
    }
}
