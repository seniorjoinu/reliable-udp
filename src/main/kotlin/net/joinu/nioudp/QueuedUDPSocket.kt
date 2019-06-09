package net.joinu.nioudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentLinkedQueue


data class QueuedDatagramPacket(val data: ByteBuffer, val address: InetSocketAddress)

class QueuedUDPSocket(bufferSize: Int = Int.MAX_VALUE) {
    private val socketDispatcher = SocketDispatcher(bufferSize)

    fun isClosed(): Boolean = socketDispatcher.state == SocketState.CLOSED

    fun listen(on: InetSocketAddress) {
        synchronized(socketDispatcher.state) {
            throwIfNotUnbound()
            socketDispatcher.bind(on)

            socketDispatcher.start()
        }
    }

    fun send(data: ByteArray, to: InetSocketAddress) {
        val buf = ByteBuffer.allocate(data.size)
        buf.put(data)
        buf.flip()

        send(buf, to)
    }

    fun send(data: ByteBuffer, to: InetSocketAddress) = send(QueuedDatagramPacket(data, to))

    fun send(packet: QueuedDatagramPacket) {
        socketDispatcher.put(packet)
    }

    fun receiveAsync(): QueuedDatagramPacket? {
        return socketDispatcher.get()
    }

    fun receiveBlocking(unblock: () -> Boolean = { false }): QueuedDatagramPacket? {
        while (!unblock()) {
            val packet = receiveAsync()
            if (packet != null)
                return packet
        }

        return null
    }

    fun receiveBlocking(timeoutMs: Long): QueuedDatagramPacket? {
        val before = System.currentTimeMillis()
        while (true) {
            val packet = receiveAsync()
            if (packet != null)
                return packet

            val after = System.currentTimeMillis()

            if (after - before >= timeoutMs)
                break
        }

        return null
    }

    fun close(timeoutMs: Long = 0) {
        synchronized(socketDispatcher.state) {
            throwIfClosed()
            socketDispatcher.close()

            if (timeoutMs == 0L)
                socketDispatcher.join()
            else
                socketDispatcher.join(timeoutMs)
        }
    }

    private fun throwIfNotUnbound() =
        check(socketDispatcher.state == SocketState.UNBOUND) { "Socket should be UNBOUND" }

    private fun throwIfClosed() = check(socketDispatcher.state != SocketState.CLOSED) { "Socket is CLOSED" }
}

class SocketDispatcher(bufferSize: Int) : Thread("SocketDispatcher-${nextDispatcherIndex()}") {
    private var channel = DatagramChannel.open()
    private val buffer = ByteBuffer.allocateDirect(bufferSize)

    private val readQueue = ConcurrentLinkedQueue<QueuedDatagramPacket>()
    private val writeQueue = ConcurrentLinkedQueue<QueuedDatagramPacket>()
    var state = SocketState.UNBOUND

    init {
        channel.configureBlocking(false)
    }

    fun bind(on: InetSocketAddress) {
        channel.bind(on)
        state = SocketState.BOUND
    }

    private fun throwIfNotBound() = check(state == SocketState.BOUND) { "Socket should be BOUND" }

    override fun run() {
        synchronized(state) {
            throwIfNotBound()
            state = SocketState.LISTENING
        }

        while (state == SocketState.LISTENING) {
            if (writeQueue.isNotEmpty()) {
                val sentPacket = writeQueue.remove()
                send(sentPacket)
            }

            if (state != SocketState.LISTENING)
                break

            val receivedPacket = receive()
            if (receivedPacket != null)
                readQueue.add(receivedPacket)
        }
    }

    fun get() = if (readQueue.isEmpty()) null else readQueue.remove()
    fun put(packet: QueuedDatagramPacket) = writeQueue.add(packet)

    private fun throwIfCannotSendOrReceive() {
        check(state == SocketState.LISTENING) { "Socket should be LISTENING" }
    }

    private fun send(packet: QueuedDatagramPacket) {
        throwIfCannotSendOrReceive()
        channel.send(packet.data, packet.address)
    }

    private fun receive(): QueuedDatagramPacket? {
        throwIfCannotSendOrReceive()
        val remoteAddress = channel.receive(buffer)

        if (buffer.position() == 0) return null

        val size = buffer.position()

        buffer.flip()

        val data = ByteBuffer.allocate(size)

        data.put(buffer)
        data.flip()

        buffer.clear()

        val from = InetSocketAddress::class.java.cast(remoteAddress)

        return QueuedDatagramPacket(data, from)
    }

    fun close() {
        state = SocketState.CLOSED
        channel.close()
    }

    companion object {
        @JvmStatic
        private fun nextDispatcherIndex() = ++dispatcherCount

        @JvmStatic
        private var dispatcherCount = 0
    }
}