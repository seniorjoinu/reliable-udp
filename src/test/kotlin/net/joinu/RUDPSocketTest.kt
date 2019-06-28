package net.joinu

import kotlinx.coroutines.*
import net.joinu.rudp.RUDPSocket
import net.joinu.rudp.runSuspending
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


class RUDPSocketTest {
    init {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    }

    @Test
    fun `different data sizes transmit well`() {
        val rudp1 = RUDPSocket(congestionControlTimeoutMs = 10)
        val rudp2 = RUDPSocket(congestionControlTimeoutMs = 10)

        val net1Addr = InetSocketAddress(1337)
        val net2Addr = InetSocketAddress(1338)

        rudp1.bind(net1Addr)
        rudp2.bind(net2Addr)

        runBlocking {
            launch { rudp1.runSuspending() }
            launch { rudp2.runSuspending() }

            rudp1.send(ByteArray(100).toDirectByteBuffer(), net2Addr)
            rudp2.receive()

            rudp2.send(ByteArray(1000000).toDirectByteBuffer(), net1Addr)
            rudp1.receive()

            coroutineContext.cancelChildren()
        }

        rudp1.close()
        rudp2.close()
    }

    @Test
    fun `multiple concurrent sends-receives work fine single-threaded`() {
        concurrentSendTest(100, 1024 * 10, 50)
    }

    @Test
    fun `multiple concurrent sends-receives work fine mutli-threaded`() {
        concurrentSendTest(1000, 1024 * 10, 1000, Dispatchers.Default)
    }

    private fun concurrentSendTest(
        n: Int,
        dataSize: Int,
        cct: Long,
        context: CoroutineContext = EmptyCoroutineContext
    ) {
        val net1Addr = InetSocketAddress(1337)
        val net2Addr = InetSocketAddress(1338)

        val rudp1 = RUDPSocket(congestionControlTimeoutMs = cct)
        rudp1.bind(net1Addr)

        val rudp2 = RUDPSocket(congestionControlTimeoutMs = cct)
        rudp2.bind(net2Addr)

        val net1Content = ByteArray(dataSize) { it.toByte() }
        val net2Content = ByteArray(dataSize) { it.toByte() }

        runBlocking(context) {
            launch { rudp1.runSuspending() }
            launch { rudp2.runSuspending() }

            coroutineScope {
                for (i in 0 until n) {
                    launch { rudp1.send(net1Content.toDirectByteBuffer(), net2Addr) }
                    launch { rudp2.send(net2Content.toDirectByteBuffer(), net1Addr) }
                    launch {
                        val k = rudp1.receive()
                        assert(k.data.toByteArray().contentEquals(net1Content)) { "Content is not the same" }
                    }
                    launch {
                        val k = rudp2.receive()
                        assert(k.data.toByteArray().contentEquals(net2Content)) { "Content is not the same" }
                    }
                }
            }

            coroutineContext.cancelChildren()
        }
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
