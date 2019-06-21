package net.joinu

import kotlinx.coroutines.*
import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.receiveBlocking
import net.joinu.rudp.runBlocking
import net.joinu.rudp.send
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer


class RUDPSocketTest {
    init {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    // this one is very slow, because of congestion control timeout
    @Test
    fun `different data sizes transmit well`() {
        val rudp1 = RUDPSocket()
        val rudp2 = RUDPSocket()

        runBlocking {
            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)

            rudp1.bind(net1Addr)
            rudp2.bind(net2Addr)

            val r1 = launch(Dispatchers.IO) {
                rudp1.runBlocking { !isActive }
            }

            val r2 = launch(Dispatchers.IO) {
                rudp2.runBlocking { !isActive }
            }

            var sentSmall = false
            rudp1.send(ByteArray(100).toDirectByteBuffer(), net2Addr) {
                sentSmall = true
            }

            rudp2.receiveBlocking()
            val receiveSmall = true

            var sentLarge = false
            rudp2.send(ByteArray(1000000).toDirectByteBuffer(), net1Addr) {
                sentLarge = true
            }

            rudp1.receiveBlocking()
            val receiveLarge = true

            while (true) {
                if (sentSmall && sentLarge && receiveSmall && receiveLarge) {
                    r1.cancel()
                    r2.cancel()
                    break
                }

                delay(100)
            }
        }

        rudp1.close()
        rudp2.close()
    }

    @Test
    fun `multiple concurrent sends-receives work fine single-threaded`() {
        val net1Addr = InetSocketAddress("localhost", 1337)
        val net2Addr = InetSocketAddress("localhost", 1338)

        val rudp1 = RUDPSocket()
        rudp1.bind(net1Addr)

        val rudp2 = RUDPSocket()
        rudp2.bind(net2Addr)

        val n = 100

        var receive1 = 0
        var sent1 = 0
        var receive2 = 0
        var sent2 = 0

        val net1Content = ByteArray(20000) { it.toByte() }
        val net2Content = ByteArray(20000) { it.toByte() }

        for (i in 0 until n) {
            rudp1.send(net1Content.toDirectByteBuffer(), net2Addr) {
                sent1++
            }

            rudp2.send(net2Content.toDirectByteBuffer(), net1Addr) {
                sent2++
            }
        }

        while (true) {
            if (sent1 == n && receive2 == n && sent2 == n && receive1 == n) {
                rudp1.close()
                rudp2.close()
                break
            }
            rudp1.runOnce()
            rudp2.runOnce()

            val k = rudp2.receive()
            if (k != null) {
                receive2++
                assert(k.data.toByteArray().contentEquals(net1Content)) { "Content is not the same" }
            }

            val k1 = rudp1.receive()
            if (k1 != null) {
                receive1++
                assert(k1.data.toByteArray().contentEquals(net2Content)) { "Content is not the same" }
            }
        }
    }

    @Test
    fun `multiple concurrent sends-receives work fine mutli-threaded`() {
        val rudp1 = RUDPSocket()
        val rudp2 = RUDPSocket()

        runBlocking {
            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)

            rudp1.bind(net1Addr)
            rudp2.bind(net2Addr)

            val n = 50

            var receive1 = 0
            var sent1 = 0
            var receive2 = 0
            var sent2 = 0

            val net1Content = ByteArray(20000) { it.toByte() }
            val net2Content = ByteArray(20000) { it.toByte() }

            val r1 = launch(Dispatchers.IO) {
                rudp1.runBlocking { !isActive }
            }

            val r2 = launch(Dispatchers.IO) {
                rudp2.runBlocking { !isActive }
            }

            for (i in 0 until n) {
                rudp1.send(net1Content, net2Addr) {
                    sent1++
                }

                rudp2.send(net2Content.toDirectByteBuffer(), net1Addr) {
                    sent2++
                }

                launch(Dispatchers.IO) {
                    rudp1.receiveBlocking()
                    receive1++
                }

                launch(Dispatchers.IO) {
                    rudp2.receiveBlocking()
                    receive2++
                }
            }

            while (true) {
                if (sent1 == n && receive2 == n && sent2 == n && receive1 == n) {
                    r1.cancel()
                    r2.cancel()
                    break
                }

                delay(100)
                println("$sent1, $receive1, $sent2, $receive2")
            }
        }

        rudp1.close()
        rudp2.close()
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

fun ByteBuffer.toByteArray(): ByteArray {
    val array = ByteArray(limit())
    get(array)

    flip()

    return array
}
