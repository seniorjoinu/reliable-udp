package net.joinu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.receiveBlocking
import net.joinu.rudp.runBlocking
import net.joinu.rudp.send
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer


class RUDPSocketTest {
    init {
        //System.setProperty("jna.debug_load", "true")
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @RepeatedTest(100)
    fun `single send-receive works fine with big data`() {
        `single send-receive works fine`(100000, 100)
    }

    @RepeatedTest(100)
    fun `single send-receive works fine with small data`() {
        `single send-receive works fine`(10, 100)
    }

    @RepeatedTest(100)
    fun `multiple concurrent sends work fine`() {
        runBlocking {
            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)
            val net1Content = ByteArray(5000) { it.toByte() }

            val rudp1 = RUDPSocket(508)
            rudp1.bind(net1Addr)

            val rudp2 = RUDPSocket(508)
            rudp2.bind(net2Addr)

            val n = 10

            var receive1 = 1
            var sent1 = 1
            var receive2 = 1
            var sent2 = 1

            val timeout = 1000000L

            val exitCondition: () -> Boolean = { sent1 > n && receive1 > n && sent2 > n && receive2 > n }

            for (i in (0 until n)) {
                rudp1.send(net1Content.toDirectByteBuffer(), net2Addr) {
                    sent1++
                }

                rudp2.send(net1Content.toDirectByteBuffer(), net1Addr) {
                    sent2++
                }

                launch(Dispatchers.IO) {
                    rudp2.receiveBlocking(timeout)
                    receive2++
                }

                launch(Dispatchers.IO) {
                    rudp1.receiveBlocking(timeout)
                    receive1++
                }
            }

            while (true) {
                if (exitCondition()) {
                    rudp1.close()
                    rudp2.close()
                    break
                }
                rudp1.runOnce()
                rudp2.runOnce()
                delay(100)
                println("$sent1, $receive1, $sent2, $receive2")
            }
        }
    }

    @Test
    fun `simple test`() {
        runBlocking {
            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)
            val net1Content = ByteArray(1000) { it.toByte() }

            val rudp1 = RUDPSocket(508)
            rudp1.bind(net1Addr)

            val rudp2 = RUDPSocket(508)
            rudp2.bind(net2Addr)

            launch(Dispatchers.IO) { rudp1.runBlocking() }
            launch(Dispatchers.IO) { rudp2.runBlocking() }

            rudp1.send(net1Content, net2Addr)
            val data = rudp2.receiveBlocking(10000)

            println()
        }
    }

    fun `single send-receive works fine`(dataSize: Int, mtu: Int) {
        /* runBlocking {
             val before = System.currentTimeMillis()

             val net1Addr = InetSocketAddress("localhost", 1337)
             val net2Addr = InetSocketAddress("localhost", 1338)
             val net1Content = ByteArray(dataSize) { it.toByte() }
             val net2Content = ByteArray(dataSize) { (100000 - it).toByte() }

             val rudp1 = RUDPSocket(mtu)
             val rudp2 = RUDPSocket(mtu)

             launch(Dispatchers.IO) {
                 rudp1.listen(net1Addr)
             }
             launch(Dispatchers.IO) {
                 rudp2.listen(net2Addr)
             }

             var sent1 = false
             var receive1 = false
             var sent2 = false
             var receive2 = false

             rudp1.onMessage { buffer, from ->
                 val bytes = ByteArray(buffer.limit())
                 buffer.get(bytes)

                 println("Net1 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                 assert(bytes.contentEquals(net2Content)) { "Content is invalid" }

                 val after = System.currentTimeMillis()
                 println("2->1 Transmission of ${dataSize / 1024f} kb took ${after - before} ms locally")

                 receive1 = true
             }

             rudp2.onMessage { buffer, from ->
                 val bytes = ByteArray(buffer.limit())
                 buffer.get(bytes)

                 println("Net2 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                 assert(bytes.contentEquals(net1Content)) { "Content is invalid" }

                 val after = System.currentTimeMillis()
                 println("1->2 Transmission of ${dataSize / 1024f} kb took ${after - before} ms locally")

                 receive2 = true
             }

             launch {
                 rudp1.send(net1Content.toDirectByteBuffer(), net2Addr)
                 sent1 = true
             }
             launch {
                 rudp2.send(net2Content.toDirectByteBuffer(), net1Addr)
                 sent2 = true
             }

             while (true) {
                 if (sent1 && sent2 && receive1 && receive2) {
                     delay(100)

                     rudp1.close()
                     rudp2.close()
                     break
                 }
                 delay(1)
             }
         }*/
    }
}

fun assertThrows(block: () -> Unit) {
    var thrown = false
    try {
        block()
    } catch (e: Throwable) {
        thrown = true
    }

    assert(thrown)
}

fun ByteArray.toDirectByteBuffer(): ByteBuffer {
    val buf = ByteBuffer.allocateDirect(this.size)
    buf.put(this)
    buf.flip()

    return buf
}
