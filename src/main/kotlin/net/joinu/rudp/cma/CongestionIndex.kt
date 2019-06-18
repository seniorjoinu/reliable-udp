package net.joinu.rudp.cma

import java.util.*


const val MIN_CONGESTION_CONTROL_TIMEOUT = 10
const val MAX_CONGESTION_CONTROL_TIMEOUT = 1000

const val MAX_WINDOW_SIZE = 10000 // MIN is MTU


data class CongestionIndex(
    var averageLatencyMs: Int = 0,
    var totalSentBlocks: Long = 0L,
    var totalDeliveredBlocks: Long = 0L,
    var lastUpdateTimestampUnix: Long = System.currentTimeMillis()
) {
    val sentBlocks = mutableMapOf<UUID, MutableSet<Pair<Int, Long>>>()
    val deliveredBlocks = mutableMapOf<UUID, MutableSet<Pair<Int, Long>>>()

    fun blockSent(threadId: UUID, blockId: Int) {
        val set = sentBlocks.getOrPut(threadId) { mutableSetOf() }
        set.add(blockId to System.currentTimeMillis())

        totalSentBlocks++
    }

    fun blockDelivered(threadId: UUID, blockId: Int) {
        val set = deliveredBlocks.getOrPut(threadId) { mutableSetOf() }
        set.add(blockId to System.currentTimeMillis())

        totalDeliveredBlocks++
    }

    fun transmissionComplete(threadId: UUID) {
        sentBlocks.remove(threadId)
        deliveredBlocks.remove(threadId)
    }

    val lossRate get() = 1f - (totalDeliveredBlocks / totalSentBlocks)

    /**
     * count total sent and total delivered on a both sides using blockId
     * calculate average latency on one side and transmit to other side
     */

    /**
     * An = Xn / N + ((N-1) * An-1) / N
     */
    private fun sequentialAverageNextValue(
        valueToAppend: Int,
        currentAverage: Int,
        currentCount: Int
    ): Pair<Int, Int> {
        val nextCount = currentCount + 1
        val nextValue = if (currentCount == 0)
            valueToAppend
        else
            valueToAppend / nextCount + currentCount * currentAverage / nextCount

        return Pair(nextValue, nextCount)
    }
}
