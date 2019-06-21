package net.joinu.rudp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
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
 * @param dataSizeBytes [Int] - if not specified [ByteArray.size] will be used
 * @param exit [ExitCallback]
 * @param complete [CompleteCallback]
 */
fun RUDPSocket.send(
    data: ByteArray,
    to: InetSocketAddress,
    dataSizeBytes: Int = 0,
    exit: ExitCallback = { false },
    complete: CompleteCallback = {}
) {
    val buffer = if (dataSizeBytes == 0)
        ByteBuffer.allocate(data.size)
    else
        ByteBuffer.allocate(dataSizeBytes)

    buffer.put(data)
    buffer.flip()

    send(buffer, to, exit, complete)
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
