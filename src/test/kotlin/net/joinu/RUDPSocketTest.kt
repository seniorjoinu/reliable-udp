package net.joinu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.joinu.rudp.ConfigurableRUDPSocket
import org.junit.jupiter.api.RepeatedTest
import java.net.InetSocketAddress
import java.nio.ByteBuffer


class RUDPSocketTest {
    init {
        //System.setProperty("jna.debug_load", "true")
        //System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @RepeatedTest(100)
    fun `single send-receive works fine`() {
        runBlocking {
            val before = System.currentTimeMillis()

            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)
            val net1Content = ByteArray(100000) { it.toByte() }
            val net2Content = ByteArray(100000) { (100000 - it).toByte() }

            val rudp1 = ConfigurableRUDPSocket(1400)
            rudp1.bind(net1Addr)

            val rudp2 = ConfigurableRUDPSocket(1400)
            rudp2.bind(net2Addr)

            launch(Dispatchers.IO) { rudp1.listen() }
            launch(Dispatchers.IO) { rudp2.listen() }

            rudp1.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net1 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net2Content)) { "Content is invalid" }

                val after = System.currentTimeMillis()
                println("2->1 Transmission of 100 kb took ${after - before} ms locally")

                rudp1.close()
            }

            rudp2.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net2 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net1Content)) { "Content is invalid" }

                val after = System.currentTimeMillis()
                println("1->2 Transmission of 100 kb took ${after - before} ms locally")

                rudp2.close()
            }

            launch {
                rudp1.send(
                    net1Content.toDirectByteBuffer(),
                    net2Addr,
                    fctTimeoutMsProvider = { 50 },
                    windowSizeProvider = { 1400 }
                )
            }
            launch {
                rudp2.send(
                    net2Content.toDirectByteBuffer(),
                    net1Addr,
                    fctTimeoutMsProvider = { 50 },
                    windowSizeProvider = { 1400 }
                )
            }
        }
    }
}

fun ByteArray.toDirectByteBuffer(): ByteBuffer {
    val buf = ByteBuffer.allocateDirect(this.size)
    buf.put(this)
    buf.flip()

    return buf
}
