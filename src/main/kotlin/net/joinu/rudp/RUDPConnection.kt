package net.joinu.rudp

import net.joinu.nioudp.RECOMMENDED_CHUNK_SIZE_BYTES
import net.joinu.rudp.cma.CongestionIndex
import java.net.InetSocketAddress
import kotlin.math.roundToInt


data class RUDPConnection(var congestionIndex: CongestionIndex, val address: InetSocketAddress) {
    /**
     * Window size in bytes - amount of bytes which can be transmitted without acknowledgement.
     * The worse connection is - the less window size is.
     *
     * Windows size varies from [RECOMMENDED_CHUNK_SIZE_BYTES] to [RECOMMENDED_CHUNK_SIZE_BYTES]*10
     */
    fun getWindowSizeBytes(): Int {
        val chunkCount = congestionIndex.getValue() * 10

        return RECOMMENDED_CHUNK_SIZE_BYTES * chunkCount.roundToInt()
    }

    /**
     * Retransmission timeout in milliseconds - how much time should one wait for acknowledgement before resend missing
     * segments.
     *
     * The worst connection is - the bigger timeout is.
     *
     * Retransmission timeout varies from latency to latency*10
     */
    fun getRetransmissionTimeoutMs(): Int {
        val minimum = congestionIndex.averageLatencyMs
        val maximum = congestionIndex.averageLatencyMs * 10

        val volume = maximum - minimum
        val difference = congestionIndex.getValue() * volume

        return (minimum + difference).roundToInt()
    }
}