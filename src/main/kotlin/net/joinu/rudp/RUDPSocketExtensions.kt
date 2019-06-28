package net.joinu.rudp

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext


/**
 * Executes [RUDPSocket.runOnce] in loop until coroutine is not canceled
 */
suspend fun RUDPSocket.runSuspending() {
    while (coroutineContext.isActive) {
        runOnce()
        delay(1)
    }
}

/**
 * [RUDPSocket.send] but instead of [ByteBuffer] it sends [ByteArray]
 *
 * @param data [ByteArray] - input data
 * @param to [InetSocketAddress] - receiver
 *
 * @return [RUDPSendContext]
 */
suspend fun RUDPSocket.send(data: ByteArray, to: InetSocketAddress): RUDPSendContext {
    val buffer = ByteBuffer.allocate(data.size)

    buffer.put(data)
    buffer.flip()

    return send(buffer, to)
}

