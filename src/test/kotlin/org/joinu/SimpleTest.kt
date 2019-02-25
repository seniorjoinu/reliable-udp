package org.joinu

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.nustaq.serialization.FSTConfiguration
import java.io.Serializable
import java.lang.RuntimeException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress


object SerializationUtils {
    val mapper = FSTConfiguration.createDefaultConfiguration()

    init {
        mapper.registerClass(NetworkPackage.NetworkPackageInner::class.java)
    }

    fun toBytes(obj: Any): ByteArray = mapper.asByteArray(obj)
    fun <T : Any> toAny(bytes: ByteArray, clazz: Class<T>): T = clazz.cast(mapper.asObject(bytes))
    inline fun <reified T : Any> toAny(bytes: ByteArray): T = SerializationUtils.toAny(bytes, T::class.java)
}

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
            val job1 = RUDP.listen(net1Addr, scope = this) { pack ->
                println("Net1 received $pack")
            }

            val job2 = RUDP.listen(net2Addr, scope = this) { pack ->
                println("Net2 received $pack")
            }

            RUDP.addHandler(net1Addr) { job1.cancel() }
            RUDP.addHandler(net2Addr) { job2.cancel() }

            val net1Content = ByteArray(10) { it.toByte() }
            val net2Content = ByteArray(10) { (10 - it).toByte() }

            RUDP.send(net1Content, net2Addr, net1Addr.port, scope = this)
            RUDP.send(net2Content, net1Addr, net2Addr.port, scope = this)
        }
    }
}

data class NetworkPackage(val data: ByteArray, val from: InetSocketAddress) {

    data class NetworkPackageInner(val data: ByteArray, val fromPort: Int) : Serializable {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NetworkPackageInner

            if (!data.contentEquals(other.data)) return false
            if (fromPort != other.fromPort) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + fromPort
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NetworkPackage

        if (!data.contentEquals(other.data)) return false
        if (from != other.from) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + from.hashCode()
        return result
    }
}

typealias NetworkPackageHandler = suspend (pack: NetworkPackage) -> Unit

object RUDP {
    suspend fun send(
        data: ByteArray,
        to: InetSocketAddress,
        listeningOnPort: Int,
        maxChunkSizeBytes: Int = 500,
        scope: CoroutineScope = GlobalScope
    ) = scope.launch {
        val pack = NetworkPackage.NetworkPackageInner(data, listeningOnPort)
        val serializedPack = SerializationUtils.toBytes(pack)

        if (serializedPack.size > maxChunkSizeBytes)
            throw IllegalArgumentException("Size of data should be less than $maxChunkSizeBytes bytes")

        val socket = DatagramSocket()
        val packet = DatagramPacket(serializedPack, serializedPack.size, to)

        withContext(Dispatchers.IO) { socket.send(packet) }
    }

    suspend fun listen(
        on: InetSocketAddress,
        maxChunkSizeBytes: Int = 500,
        scope: CoroutineScope = GlobalScope,
        handler: NetworkPackageHandler? = null
    ) = scope.launch {
        val socket = DatagramSocket(on)

        val buffer = ByteArray(maxChunkSizeBytes)

        println("Listening on: $on")

        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            withContext(Dispatchers.IO) { socket.receive(packet) }

            val netPackageInner = SerializationUtils.toAny<NetworkPackage.NetworkPackageInner>(packet.data)
            val netPackage =
                NetworkPackage(netPackageInner.data, InetSocketAddress(packet.address, netPackageInner.fromPort))

            if (handler != null) handler(netPackage)
            if (onMessageHandlers.containsKey(on)) onMessageHandlers[on]!!.forEach { it(netPackage) }
        }
    }

    private val onMessageHandlers: MutableMap<InetSocketAddress, MutableList<NetworkPackageHandler>> = mutableMapOf()

    fun addHandler(whenListenOn: InetSocketAddress, handler: NetworkPackageHandler) {
        if (!onMessageHandlers.containsKey(whenListenOn))
            onMessageHandlers[whenListenOn] = mutableListOf()

        onMessageHandlers[whenListenOn]!!.add(handler)
    }
}
