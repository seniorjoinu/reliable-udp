package net.joinu.utils

import java.util.*

internal object Rng {
    val rng by lazy { Random() }
}

fun Random.nextFloat(from: Float = 0f, to: Float = 1f): Float {
    require(from < to) { "'from' should be lest than 'to'" }
    return from + (nextFloat() / (1 / (to - from)))
}