package org.joinu

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.lang.RuntimeException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.*


class SimpleTest {
    @Test
    fun `serialization works fine`() {
        val pack = NetworkPackage.NetworkPackageInner(ByteArray(10) { it.toByte() }, 123)

        val serialized = SerializationUtils.toBytes(pack)
        val deserialized = SerializationUtils.toAny<NetworkPackage>(serialized)

        println("Serialized: ${serialized.joinToString { String.format("%02X", it) }}")
        println("Deserialized: $deserialized")

        assert(deserialized.data.contentEquals(pack.data))
    }

    @Test
    fun `send and receive works fine`() {
        val net1Addr = InetSocketAddress("localhost", 1337)
        val net2Addr = InetSocketAddress("localhost", 1338)

        runBlocking {
            val socket1 = DatagramSocket(net1Addr)
            launch { UDP.listen(socket1) }
            println("Listening on port ${net1Addr.port}")

            val socket2 = DatagramSocket(net2Addr)
            launch { UDP.listen(socket2) }
            println("Listening on port ${net2Addr.port}")

            UDP.onMessage(net1Addr) { bytes, from ->
                println("Net1 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                socket1.close()
            }

            UDP.onMessage(net2Addr) { bytes, from ->
                println("Net2 received ${bytes.joinToString { String.format("%02X", it) }} from $from")
                socket2.close()
            }

            val net1Content = ByteArray(10) { it.toByte() }
            val net2Content = ByteArray(10) { (10 - it).toByte() }

            UDP.send(net1Content, net2Addr)
            UDP.send(net2Content, net1Addr)
        }

        println("end of test")
    }

    private fun udpStress(packetCount: Int, packetSizeBytes: Int, timeoutMs: Long) {
        val net1Addr = InetSocketAddress("localhost", 1337)

        val lostCount = runBlocking {
            var count = packetCount
            val socket1 = DatagramSocket(net1Addr)

            val countTask = async(Dispatchers.IO) {
                delay(timeoutMs)
                socket1.close()
                count
            }

            launch(Dispatchers.IO) { UDP.listen(socket1, packetSizeBytes + 1) { isActive } }

            UDP.onMessage(net1Addr) { bytes, from ->
                count--

                if (count == 0)
                    socket1.close()
            }

            for (i in 0..packetCount) {
                val net2Content = ByteArray(packetSizeBytes)
                Random().nextBytes(net2Content)
                UDP.send(net2Content, net1Addr, packetSizeBytes + 1)
            }

            return@runBlocking countTask.await()
        }

        println("$packetCount packets $packetSizeBytes bytes each timeout $timeoutMs ms: lost $lostCount (${lostCount.toDouble() / packetCount * 100}%)")
    }

    @Test
    fun `udp packet loss benchmark`() {
        //udpStress(1000, 10, 2000)
        //udpStress(1000, 50, 2000)
        //udpStress(1000, 100, 2000)
        //udpStress(1000, 500, 2000)
        //udpStress(1000, 1000, 2000)
        //udpStress(1000, 5000, 2000)
        udpStress(1000, 500000, 2000)
    }

    @Test
    fun asdtest() {
        runBlocking {
            launch(Dispatchers.IO) { Thread.sleep(1000) }
            throw RuntimeException("kek")
        }
    }
}
// TODO: packet lost benchmark