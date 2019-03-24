package net.joinu

import net.joinu.rudp.cma.CongestionIndex
import net.joinu.rudp.cma.MAX_POSSIBLE_LATENCY
import net.joinu.utils.Rng.rng
import net.joinu.utils.nextFloat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test


class CongestionIndexTest {
    @Test
    fun `creates with correct values`() {
        val index = CongestionIndex()

        assert(index.averageNeighboursCongestionIndex == 1.0f)
        assert(index.neighboursCongestionIndexInstanceCount == 0)
        assert(index.averageLatencyMs == MAX_POSSIBLE_LATENCY)
        assert(index.latencyInstanceCount == 0)
        assert(index.retransmissionRate == 1.0f)
        assert(index.retransmissionInstanceCount == 0)
        assert(index.getValue() == 1.0f)
    }

    @Test
    fun `updates correctly`() {
        val index = CongestionIndex()

        index.updateAverageLatency(150f)
        index.updateAverageNeighboursCongestionIndex(0.2f)
        index.updateRetransmissionRate(false)

        assert(index.getValue() !in (0.4..1.0))
    }

    @RepeatedTest(1000)
    fun `value distributes correctly`() {
        val goodNetworkWithoutRetransmissionsAndLowLatency = CongestionIndex(
            averageNeighboursCongestionIndex = rng.nextFloat(0f, 0.1f),
            averageLatencyMs = rng.nextFloat(5f, 50f),
            retransmissionRate = 0f
        )

        val goodNetworkWithLittleRetransmissionsAndAverageLatency = CongestionIndex(
            averageNeighboursCongestionIndex = rng.nextFloat(0.1f, 0.2f),
            averageLatencyMs = rng.nextFloat(50f, 150f),
            retransmissionRate = rng.nextFloat(0.1f, 0.2f)
        )

        val averageNetworkWithHighRetransmissionsAndHighLatency = CongestionIndex(
            averageNeighboursCongestionIndex = rng.nextFloat(0.3f, 0.5f),
            averageLatencyMs = rng.nextFloat(150f, 300f),
            retransmissionRate = rng.nextFloat(0.4f, 0.6f)
        )

        val badNetworkWithHugeRetransmissionsAndHugeLatency = CongestionIndex(
            averageNeighboursCongestionIndex = rng.nextFloat(0.5f, 1.0f),
            averageLatencyMs = rng.nextFloat(300f, MAX_POSSIBLE_LATENCY),
            retransmissionRate = rng.nextFloat(0.8f, 1.0f)
        )

        assert(goodNetworkWithoutRetransmissionsAndLowLatency.getValue() in (0.0..0.1)) { "Actually $goodNetworkWithoutRetransmissionsAndLowLatency = ${goodNetworkWithoutRetransmissionsAndLowLatency.getValue()}" }
        assert(goodNetworkWithLittleRetransmissionsAndAverageLatency.getValue() in (0.05..0.25)) { "Actually $goodNetworkWithLittleRetransmissionsAndAverageLatency = ${goodNetworkWithLittleRetransmissionsAndAverageLatency.getValue()}" }
        assert(averageNetworkWithHighRetransmissionsAndHighLatency.getValue() in (0.3..0.7)) { "Actually $averageNetworkWithHighRetransmissionsAndHighLatency = ${averageNetworkWithHighRetransmissionsAndHighLatency.getValue()}" }
        assert(badNetworkWithHugeRetransmissionsAndHugeLatency.getValue() in (0.6..1.0)) { "Actually $badNetworkWithHugeRetransmissionsAndHugeLatency = ${badNetworkWithHugeRetransmissionsAndHugeLatency.getValue()}" }
    }
}