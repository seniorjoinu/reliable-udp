package net.joinu

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.joinu.nioudp.QueuedUDPSocket
import org.junit.jupiter.api.RepeatedTest
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

    @RepeatedTest(100)
    fun `multiple subsequent send and receive work fine`() {
        val bufSize = 200
        val repeats = 100

        val socket1 = QueuedUDPSocket(bufSize)
        val socket2 = QueuedUDPSocket(bufSize)

        val addr1 = InetSocketAddress("localhost", 1337)
        val addr2 = InetSocketAddress("localhost", 1338)

        socket1.listen(addr1)
        socket2.listen(addr2)

        val data1 = ByteArray(199) { it.toByte() }
        val data2 = ByteArray(100) { it.toByte() }

        for (i in 0 until repeats) {
            socket1.send(data1, addr2)
        }

        for (i in 0 until repeats) {
            socket2.send(data2, addr1)
        }

        for (i in 0 until repeats) {
            val (data2Received, addr2Received) = socket1.receiveBlocking()!!.toPairByteArray()
            assert(data2.contentEquals(data2Received)) { "data2 is not equal" }
            println("$i ${data2Received.contentToString()}")
        }

        for (i in 0 until repeats) {
            val (data1Received, addr1Received) = socket2.receiveBlocking()!!.toPairByteArray()
            assert(data1.contentEquals(data1Received)) { "data1 is not equal" }
            println("$i ${data1Received.contentToString()}")
        }

        socket1.close()
        socket2.close()
    }

    @RepeatedTest(100)
    fun `multiple parallel send and receive work fine`() {
        runBlocking {
            val bufSize = 200
            val repeats = 100

            val socket1 = QueuedUDPSocket(bufSize)
            val socket2 = QueuedUDPSocket(bufSize)

            val addr1 = InetSocketAddress("localhost", 1337)
            val addr2 = InetSocketAddress("localhost", 1338)

            socket1.listen(addr1)
            socket2.listen(addr2)

            val data1 = ByteArray(199) { it.toByte() }
            val data2 = ByteArray(100) { it.toByte() }

            for (i in 0 until repeats) {
                launch { socket1.send(data1, addr2) }
                launch { socket2.send(data2, addr1) }
            }

            var received1 = 0
            var received2 = 0

            for (i in 0 until repeats) {
                launch {
                    val (data2Received, addr2Received) = socket1.receiveBlocking()!!.toPairByteArray()
                    assert(data2.contentEquals(data2Received)) { "data2 is not equal" }
                    println("$i ${data2Received.contentToString()}")
                    received1++
                }
                launch {
                    val (data1Received, addr1Received) = socket2.receiveBlocking()!!.toPairByteArray()
                    assert(data1.contentEquals(data1Received)) { "data1 is not equal" }
                    println("$i ${data1Received.contentToString()}")
                    received2++
                }
            }

            while (true) {
                if (received1 >= 99 && received2 >= 99) {
                    socket1.close()
                    socket2.close()
                    break
                }
                delay(1)
            }
        }
    }
}