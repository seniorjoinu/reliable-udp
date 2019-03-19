package net.joinu.rudp.cma

import java.time.Instant


/**
 * I've tried to check some places far away from me (like Louisiana and Texas) but maximum I get
 * with speedtest.net is 260+ ms. So, we'll pick in advance.
 */
const val MAX_POSSIBLE_LATENCY = 3000

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
 */
data class CongestionIndex(
    val neighboursCongestionIndexes: List<Float>, // list<0-1>
    val latencyMs: Int, // 0-maxLatency
    val retransmissionRate: Float, // 0-1
    val timestamp: Instant = Instant.now()
) {
    init {
        require(neighboursCongestionIndexes.all { it in 0.0..1.0 }) { "Invalid neighbours' congestion indexes (not in 0..1)" }
        require(latencyMs >= 0) { "Invalid latency (less than 0)" }
        require(retransmissionRate in 0.0..1.0) { "Invalid latency (not in 0..1)" }
    }

    fun getValue(): Float {
        val averageNeighbourCongestionIndex = neighboursCongestionIndexes
            .fold(0.0) { acc, it -> acc + it } / neighboursCongestionIndexes.size // 0-1

        val normalizedLatency = latencyMs.toFloat() / MAX_POSSIBLE_LATENCY // 0-1

        // priorities are:
        //      if latency is low (0-50 ms) normalizedLatency < averageNeighbourCongestionIndex << retransmissionRate
        //      if latency is ok (51-150 ms) normalizedLatency ~ averageNeighbourCongestionIndex << retransmissionRate
        //      if latency is high (150+ ms) averageNeighbourCongestionIndex < normalizedLatency << retransmissionRate
        // TODO: find better formula

        val tiny = 0.05
        val regular = 0.25
        val small = (tiny + regular) / 2
        val big = 1 - (tiny + regular)

        return when (latencyMs) {
            in 0..50 -> tiny * normalizedLatency + regular * averageNeighbourCongestionIndex + big * retransmissionRate
            in 51..150 -> small * normalizedLatency + small * averageNeighbourCongestionIndex + big * retransmissionRate
            else -> tiny * averageNeighbourCongestionIndex + regular * normalizedLatency + big * retransmissionRate
        }.toFloat()
    }
}