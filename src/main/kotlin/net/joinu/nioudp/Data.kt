package net.joinu.nioudp

import java.net.InetSocketAddress

typealias NetworkMessageHandler = (bytes: ByteArray, from: InetSocketAddress) -> Unit

enum class SocketState {
    UNBOUND, BOUND, CLOSED
}

const val MAX_CHUNK_SIZE_BYTES = 65_507
const val RECOMMENDED_CHUNK_SIZE_BYTES = 504
const val DATA_SIZE_BYTES = 4