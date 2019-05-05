package net.joinu

import kotlinx.coroutines.*
import net.joinu.nioudp.AsyncUDPSocket
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.*


class NonBlockingUdpTest {
    init {
        //System.setProperty("jna.debug_load", "true")
        //System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @RepeatedTest(100)
    fun `send and receive work fine`() {
        runBlocking {
            val before = System.currentTimeMillis()

            val net1Addr = InetSocketAddress("localhost", 1337)
            val net2Addr = InetSocketAddress("localhost", 1338)
            val net1Content = ByteArray(10000) { it.toByte() }
            val net2Content = ByteArray(10000) { (10000 - it).toByte() }

            val udp1 = AsyncUDPSocket()
            udp1.bind(net1Addr)

            val udp2 = AsyncUDPSocket()
            udp2.bind(net2Addr)

            launch(Dispatchers.IO) { udp1.listen() }
            launch(Dispatchers.IO) { udp2.listen() }

            udp1.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net1 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net2Content)) { "Content is invalid" }

                val after = System.currentTimeMillis()
                println("2->1 Transmission of 10kb took ${after - before} ms locally")

                udp1.close()
            }

            udp2.onMessage { buffer, from ->
                val bytes = ByteArray(buffer.limit())
                buffer.get(bytes)

                println("Net2 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                assert(bytes.contentEquals(net1Content)) { "Content is invalid" }

                val after = System.currentTimeMillis()
                println("1->2 Transmission of 10kb took ${after - before} ms locally")

                udp2.close()
            }

            launch {
                udp1.send(net1Content.toDirectByteBuffer(), net2Addr)
            }
            launch {
                udp2.send(net2Content.toDirectByteBuffer(), net1Addr)
            }
        }

        println("end of test")
    }

    var resultCount = 0

    @Throws(IllegalArgumentException::class)
    private fun udpStress(packetCount: Int, packetSizeBytes: Int, timeoutMs: Long) {
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                resultCount = packetCount
                val net1Addr = InetSocketAddress("localhost", 1337)
                val udp1 = AsyncUDPSocket()
                udp1.bind(net1Addr)

                launch(Dispatchers.IO) {
                    delay(timeoutMs)
                    udp1.close()
                }
                launch(Dispatchers.IO) { udp1.listen() }

                udp1.onMessage { bytes, from ->
                    resultCount--

                    if (resultCount == 0)
                        udp1.close()
                }

                for (i in 1..packetCount) {
                    val net2Content = ByteArray(packetSizeBytes)
                    Random().nextBytes(net2Content)

                    udp1.send(net2Content.toDirectByteBuffer(), net1Addr)
                }
            }
        }

        println("$packetCount packets $packetSizeBytes bytes each timeout $timeoutMs ms: lost $resultCount (${resultCount.toDouble() / packetCount * 100}%)")
    }

    //@Test
    fun `udp packet loss benchmark`() {
        udpStress(1000, 10, 2000)
        udpStress(1000, 50, 2000)
        udpStress(1000, 100, 2000)
        udpStress(1000, 500, 2000)
        udpStress(1000, 1000, 2000)
        udpStress(1000, 5000, 2000)
        assertThrows<java.lang.IllegalArgumentException> {
            udpStress(1000, 500000, 2000)
        }
    }
}
