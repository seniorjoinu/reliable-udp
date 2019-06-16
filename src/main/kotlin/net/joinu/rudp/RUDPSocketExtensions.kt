package net.joinu.rudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException


fun RUDPSocket.runBlocking(exit: () -> Boolean = { false }) {
    while (!exit() || !isClosed()) {
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
fun RUDPSocket.sendBlocking(data: ByteArray, to: InetSocketAddress, timeoutMs: Long, dataSizeBytes: Int = 0) {
    val buffer = if (dataSizeBytes == 0)
        ByteBuffer.allocate(data.size)
    else
        ByteBuffer.allocate(dataSizeBytes)

    buffer.put(data)
    buffer.flip()

    sendBlocking(buffer, to, timeoutMs)
}

@Throws(TimeoutException::class)
fun RUDPSocket.sendBlocking(data: ByteBuffer, to: InetSocketAddress, timeoutMs: Long) {
    /* val start = System.currentTimeMillis()
     var sent = false
     var timeouted = false

     val stopLambda: RUDPSendContext.() -> Boolean = {
         if (start + timeoutMs > System.currentTimeMillis())
             timeouted = true

         timeouted
     }

     send(data, to, stopLambda) { sent = true }

     while (!sent) {
         if (timeouted)
             throw TimeoutException("Send timed out")
     }*/
}

@Throws(TimeoutException::class)
fun RUDPSocket.receiveBlocking(timeoutMs: Long): QueuedDatagramPacket {
    val start = System.currentTimeMillis()

    while (true) {
        val packet = receive()

        if (packet != null)
            return packet

        if (start + timeoutMs < System.currentTimeMillis())
            throw TimeoutException("Unable to receive any packet")
    }
}
