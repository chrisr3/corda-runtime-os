package net.corda.messagebus.db.serialization

import net.corda.avro.serialization.CordaAvroSerializer
import net.corda.schema.registry.AvroSchemaRegistry
import net.corda.v5.base.exceptions.CordaRuntimeException
import org.slf4j.LoggerFactory

class CordaDBAvroSerializerImpl<T : Any>(
    private val schemaRegistry: AvroSchemaRegistry,
    private val onError: ((ByteArray) -> Unit)?
) : CordaAvroSerializer<T> {

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun serialize(data: T?): ByteArray? {

        try {
            return when (data) {
                is String -> data.encodeToByteArray()
                is ByteArray -> data
                null -> null
                else -> schemaRegistry.serialize(data).array()
            }
        } catch (ex: Throwable) {
            val message = "Failed to serialize instance of class type ${data?.javaClass?.name}"

            onError?.invoke(message.toByteArray())
            log.error(message, ex)
            throw CordaRuntimeException(message, ex)
        }
    }
}
