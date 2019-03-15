package org.joinu

import org.nustaq.serialization.FSTConfiguration
import kotlin.reflect.KClass

object SerializationUtils {
    val mapper = FSTConfiguration.createDefaultConfiguration()

    fun <T : Any> registerClass(vararg clazz: KClass<T>) = mapper.registerClass(*(clazz.map { it.java }.toTypedArray()))
    fun toBytes(obj: Any): ByteArray = mapper.asByteArray(obj)
    fun <T : Any> toAny(bytes: ByteArray, clazz: Class<T>): T = clazz.cast(mapper.asObject(bytes))
    inline fun <reified T : Any> toAny(bytes: ByteArray): T = SerializationUtils.toAny(bytes, T::class.java)
}