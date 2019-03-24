package net.joinu.rudp.cma

import java.lang.Math.round
import java.time.Instant


/**
 * I've tried to check some places far away from me (like Louisiana and Texas) but maximum I get
 * with speedtest.net is 260+ ms. So, we'll pick in advance.
 */
const val MAX_POSSIBLE_LATENCY = 1000F

/**
 *  Congestion Measurement Algorithm (CMA):
 *  Each node has it's local Congestion Index 0.0-1.0 where 0.0 means very low network congestion (e.g.
 *  local network) and 1.0 means very high network congestion (e.g. wireless lossy ad-hoc network). Values [0-0.2] mean
 *  very good, values [0.2-0.8] mean mediocre, values [0.8-1.0] mean very bad. Congestion Index of node is average median
 *  (distribution maximum) of local Congestion Indexes of node's neighbours. Node calculates local Congestion Indexes
 *  of it's neighbours using next values: neighbour's average Congestion Index, latency between the node and it's neighbour
 *  and retransmission rate. By default (when the node has no neighbours) it considers itself in a bad network and randomly
 *  chooses Congestion Index from range [0.8-1.0].
 *  When local Congestion Index is calculated it can be used to optimize retransmission timeouts and retransmission window
 *  size.
 *
 *  @param averageNeighboursCongestionIndex: [Float] in (0..1), default = 1.0f - get all the values from other
 *      congestion indexes you know and make an average from them
 *  @param averageLatencyMs: [Float] in (0..1), default = [MAX_POSSIBLE_LATENCY] - measure all latencies (time between
 *      sending and receiving any particular package segment) and make an average from them
 *  @param retransmissionRate: [Float] in (0..1), default = 0 - how much retransmissions happened divided by total sent
 *      segment count
 *  @param lastUpdateTimestamp: [Instant], default = [Instant.now] - when the last update happened to this congestion
 *      index
 */
data class CongestionIndex(
    var averageNeighboursCongestionIndex: Float = 1.0f, // 0-1
    var neighboursCongestionIndexInstanceCount: Int = 0,
    var averageLatencyMs: Float = MAX_POSSIBLE_LATENCY, // 0-MAX_POSSIBLE_LATENCY
    var latencyInstanceCount: Int = 0,
    var retransmissionRate: Float = 1.0f, // 0-1
    var retransmissionInstanceCount: Int = 0,
    var lastUpdateTimestamp: Instant = Instant.now()
) {
    init {
        require(averageNeighboursCongestionIndex in 0.0..1.0) { "Invalid neighbours' congestion indexes (not in 0..1)" }
        require(averageLatencyMs >= 0) { "Invalid latency (less than 0)" }
        require(retransmissionRate in 0.0..1.0) { "Invalid retransmission rate (not in 0..1)" }
    }

    companion object {
        val priority = object : Priorities {
            override val tiny = 0.1f
            override val regular = 0.2f
            override val small = (tiny + regular) / 2
            override val big = 1 - (tiny + regular)
        }
    }

    fun getValue(): Float {
        val normalizedLatency = if (averageLatencyMs > MAX_POSSIBLE_LATENCY)
            1f
        else
            averageLatencyMs / MAX_POSSIBLE_LATENCY // 0-1

        // priorities are:
        //      if latency is low (0-50 ms) normalizedLatency < averageNeighbourCongestionIndex << retransmissionRate
        //      if latency is ok (51-150 ms) normalizedLatency ~ averageNeighbourCongestionIndex << retransmissionRate
        //      if latency is high (150-MAX_POSSIBLE_LATENCY ms) averageNeighbourCongestionIndex < normalizedLatency << retransmissionRate
        // TODO: find better formula
        return when (round(averageLatencyMs)) {
            in 0..50 -> priority.tiny * normalizedLatency + priority.regular * averageNeighboursCongestionIndex +
                    priority.big * retransmissionRate

            in 50..150 -> priority.small * normalizedLatency + priority.small * averageNeighboursCongestionIndex +
                    priority.big * retransmissionRate

            else -> priority.tiny * averageNeighboursCongestionIndex + priority.regular * normalizedLatency +
                    priority.big * retransmissionRate
        }
    }

    /**
     * Updates average latency using sequential average formula
     */
    fun updateAverageLatency(newLatency: Float) {
        val (nextValue, nextCount) = sequentialAverageNextValue(newLatency, averageLatencyMs, latencyInstanceCount)

        averageLatencyMs = nextValue
        latencyInstanceCount = nextCount
        lastUpdateTimestamp = Instant.now()
    }

    /**
     * Updates average neighbours' congestion index using sequential average formula
     */
    fun updateAverageNeighboursCongestionIndex(newNeighboursCongestionIndex: Float) {
        val (nextValue, nextCount) = sequentialAverageNextValue(
            newNeighboursCongestionIndex,
            averageNeighboursCongestionIndex,
            neighboursCongestionIndexInstanceCount
        )

        averageNeighboursCongestionIndex = nextValue
        neighboursCongestionIndexInstanceCount = nextCount
        lastUpdateTimestamp = Instant.now()
    }

    /**
     * Updates retransmission rate using sequential average formula
     */
    fun updateRetransmissionRate(needsRetransmission: Boolean) {
        val (nextValue, nextCount) = if (needsRetransmission)
            sequentialAverageNextValue(1f, retransmissionRate, retransmissionInstanceCount)
        else
            sequentialAverageNextValue(0f, retransmissionRate, retransmissionInstanceCount)

        retransmissionRate = nextValue
        retransmissionInstanceCount = nextCount
        lastUpdateTimestamp = Instant.now()
    }

    /**
     * An = Xn / N + ((N-1) * An-1) / N
     */
    private fun sequentialAverageNextValue(
        valueToAppend: Float,
        currentAverage: Float,
        currentCount: Int
    ): Pair<Float, Int> {
        val nextCount = currentCount + 1
        val nextValue = if (currentCount == 0)
            valueToAppend
        else
            valueToAppend / nextCount + currentCount * currentAverage / nextCount

        return Pair(nextValue, nextCount)
    }
}

interface Priorities {
    val tiny: Float
    val small: Float
    val regular: Float
    val big: Float
}