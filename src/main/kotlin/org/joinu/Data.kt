package org.joinu

import java.io.Serializable
import java.net.InetSocketAddress

data class NetworkPackage(val data: ByteArray, val from: InetSocketAddress) {

    data class NetworkPackageInner(val data: ByteArray, val fromPort: Int) : Serializable {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NetworkPackageInner

            if (!data.contentEquals(other.data)) return false
            if (fromPort != other.fromPort) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + fromPort
            return result
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NetworkPackage

        if (!data.contentEquals(other.data)) return false
        if (from != other.from) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + from.hashCode()
        return result
    }
}