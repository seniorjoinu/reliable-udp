package net.joinu

import net.joinu.utils.SerializationUtils
import org.junit.jupiter.api.Test


class SerializationTest {
    @Test
    fun `serialization works fine`() {
        val pack = "Im going to be serialized"

        val serialized = SerializationUtils.toBytes(pack)
        val deserialized = SerializationUtils.toAny<String>(serialized)

        println("Serialized: ${serialized.joinToString { String.format("%02X", it) }}")
        println("Deserialized: $deserialized")

        assert(deserialized == pack)
    }
}