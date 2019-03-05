package org.joinu

import org.nustaq.serialization.FSTConfiguration

object SerializationUtils {
    val mapper = FSTConfiguration.createDefaultConfiguration()

    init {
        mapper.registerClass(NetworkPackage.NetworkPackageInner::class.java)
    }

    fun toBytes(obj: Any): ByteArray = mapper.asByteArray(obj)
    fun <T : Any> toAny(bytes: ByteArray, clazz: Class<T>): T = clazz.cast(mapper.asObject(bytes))
    inline fun <reified T : Any> toAny(bytes: ByteArray): T = SerializationUtils.toAny(bytes, T::class.java)
}