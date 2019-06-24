package net.joinu.rudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException


/**
 * Blocks current thread running [RUDPSocket]'s processing loop until [exit] condition is met.
 *
 * @param exit lambda () -> [Boolean] - when returns true processing loop completes (it still be run after)
 */
fun RUDPSocket.runBlocking(exit: () -> Boolean = { false }) {
    while (!exit()) {
        runOnce()
    }
}

/**
 * [RUDPSocket.send] but instead of [ByteBuffer] it sends [ByteArray]
 *
 * @param data [ByteArray] - input data
 * @param to [InetSocketAddress] - receiver
 *
 * @return [CompletableFuture] of [RUDPSendContext] - future that completes when send succeeds,
 *  completes exceptionally when socket closed before send completes, and can be canceled (that will cancel sending)
 */
fun RUDPSocket.send(data: ByteArray, to: InetSocketAddress): CompletableFuture<RUDPSendContext> {
    val buffer = ByteBuffer.allocate(data.size)

    buffer.put(data)
    buffer.flip()

    return send(buffer, to)
}

/**
 * Blocks current thread until it receives something from the socket.
 *
 * @param timeoutMs [Long] - if not specified runs forever
 *
 * @return [QueuedDatagramPacket]
 * @throws [TimeoutException]
 */
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
