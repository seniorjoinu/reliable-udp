package net.joinu

import net.joinu.nioudp.RECOMMENDED_CHUNK_SIZE_BYTES
import net.joinu.rudp.RUDPConnection
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.math.roundToInt


class RUDPConnectionTest {
    @Test
    fun `created assuming very bad network`() {
        val connection = RUDPConnection(InetSocketAddress(123))

        assert(connection.getWindowSizeBytes() == RECOMMENDED_CHUNK_SIZE_BYTES)
        assert(connection.getRetransmissionTimeoutMs() == (connection.congestionIndex.averageLatencyMs * 10).roundToInt())
    }

    @Test
    fun `value changes according to congestion index`() {
        val goodConnection = RUDPConnection(InetSocketAddress(123))

        goodConnection.congestionIndex.retransmissionRate = 0.1f
        goodConnection.congestionIndex.averageLatencyMs = 40f
        goodConnection.congestionIndex.averageNeighboursCongestionIndex = 0.1f

        val averageConnection = RUDPConnection(InetSocketAddress(123))

        averageConnection.congestionIndex.retransmissionRate = 0.3f
        averageConnection.congestionIndex.averageLatencyMs = 120f
        averageConnection.congestionIndex.averageNeighboursCongestionIndex = 0.3f

        val badConnection = RUDPConnection(InetSocketAddress(123))

        badConnection.congestionIndex.retransmissionRate = 0.6f
        badConnection.congestionIndex.averageLatencyMs = 300f
        badConnection.congestionIndex.averageNeighboursCongestionIndex = 0.5f

        assert(goodConnection.getWindowSizeBytes() in (RECOMMENDED_CHUNK_SIZE_BYTES * 9..RECOMMENDED_CHUNK_SIZE_BYTES * 10)) { "Actually ${goodConnection.getWindowSizeBytes()}" }
        assert(goodConnection.getRetransmissionTimeoutMs() in goodConnection.congestionIndex.averageLatencyMs.roundToInt()..(goodConnection.congestionIndex.averageLatencyMs * 2).roundToInt()) { "Actually ${goodConnection.getRetransmissionTimeoutMs()}" }

        assert(averageConnection.getWindowSizeBytes() in (RECOMMENDED_CHUNK_SIZE_BYTES * 7..RECOMMENDED_CHUNK_SIZE_BYTES * 9)) { "Actually ${averageConnection.getWindowSizeBytes()}" }
        assert(averageConnection.getRetransmissionTimeoutMs() in (averageConnection.congestionIndex.averageLatencyMs * 2).roundToInt()..(averageConnection.congestionIndex.averageLatencyMs * 4).roundToInt()) { "Actually ${averageConnection.getRetransmissionTimeoutMs()}" }

        assert(badConnection.getWindowSizeBytes() in (RECOMMENDED_CHUNK_SIZE_BYTES * 4..RECOMMENDED_CHUNK_SIZE_BYTES * 7)) { "Actually ${badConnection.getWindowSizeBytes()}" }
        assert(badConnection.getRetransmissionTimeoutMs() in (badConnection.congestionIndex.averageLatencyMs * 5).roundToInt()..(badConnection.congestionIndex.averageLatencyMs * 7).roundToInt()) { "Actually ${badConnection.getRetransmissionTimeoutMs()}" }
    }
}