package net.joinu.rudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException


fun RUDPSocket.runBlocking(exit: () -> Boolean = { false }) {
    while (!exit()) {
        runOnce()
    }
}

fun RUDPSocket.send(
    data: ByteArray,
    to: InetSocketAddress,
    dataSizeBytes: Int = 0,
    exit: RUDPSendContext.() -> Boolean = { false },
    complete: RUDPSendContext.() -> Unit = {}
) {
    val buffer = if (dataSizeBytes == 0)
        ByteBuffer.allocate(data.size)
    else
        ByteBuffer.allocate(dataSizeBytes)

    buffer.put(data)
    buffer.flip()

    send(buffer, to, exit, complete)
}

@Throws(TimeoutException::class)
fun RUDPSocket.receiveBlocking(timeoutMs: Long = 0): QueuedDatagramPacket {
    val start = System.currentTimeMillis()

    while (true) {
        val packet = receive()

        if (packet != null)
            return packet

        if (timeoutMs > 0 && start + timeoutMs < System.currentTimeMillis())
            throw TimeoutException("Unable to receive any packet")
    }
}
