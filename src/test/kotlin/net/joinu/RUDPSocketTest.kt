package net.joinu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.joinu.rudp.RUDPSocket
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer


object RUDPSocketTest {
    init {
        System.setProperty("jna.debug_load", "true")
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @Test
    fun `single send-receive works fine`() {
        runBlocking {
            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)
            val net1Content = ByteArray(10000) { it.toByte() }
            val net2Content = ByteArray(10000) { (10000 - it).toByte() }

            val rudp1 = RUDPSocket()
            rudp1.bind(net1Addr)

            val rudp2 = RUDPSocket()
            rudp2.bind(net2Addr)

            println("Sockets bound")

            launch(Dispatchers.IO) { rudp1.listen() }
            launch(Dispatchers.IO) { rudp2.listen() }

            println("Sockets are listening")

            rudp1.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net1 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net2Content)) { "Content is invalid" }

                // TODO: fix invalid data

                rudp1.close()
                rudp2.close()
            }

            rudp2.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net2 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net1Content)) { "Content is invalid" }
            }

            println("Handlers set")

            delay(100)

            rudp1.send(net1Content.toDirectByteBuffer(), net2Addr)
            rudp2.send(net2Content.toDirectByteBuffer(), net1Addr)

            println("Data sent")
        }
    }
}

fun ByteArray.toDirectByteBuffer(): ByteBuffer {
    val buf = ByteBuffer.allocateDirect(this.size)
    buf.put(this)
    buf.flip()

    return buf
}
