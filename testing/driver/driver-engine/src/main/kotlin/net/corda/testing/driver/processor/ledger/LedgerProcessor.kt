package net.corda.testing.driver.processor.ledger

import java.util.Collections.singletonList
import net.corda.avro.serialization.CordaAvroSerializationFactory
import net.corda.data.ledger.persistence.LedgerPersistenceRequest
import net.corda.ledger.persistence.processor.DelegatedRequestHandlerSelector
import net.corda.ledger.persistence.processor.LedgerPersistenceRequestProcessor
import net.corda.messaging.api.records.Record
import net.corda.persistence.common.EntitySandboxService
import net.corda.persistence.common.ResponseFactory
import net.corda.sandboxgroupcontext.CurrentSandboxGroupContext
import net.corda.testing.driver.processor.ExternalProcessor
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

@Component(service = [ LedgerProcessor::class ])
class LedgerProcessor @Activate constructor(
    @Reference
    currentSandboxGroupContext: CurrentSandboxGroupContext,
    @Reference
    entitySandboxService: EntitySandboxService,
    @Reference
    delegatedRequestHandlerSelector: DelegatedRequestHandlerSelector,
    @Reference
    responseFactory: ResponseFactory,
    @Reference
    cordaAvroSerializationFactory: CordaAvroSerializationFactory
) : ExternalProcessor {
    private val stringDeserializer = cordaAvroSerializationFactory.createAvroDeserializer({}, String::class.java)
    private val anyDeserializer = cordaAvroSerializationFactory.createAvroDeserializer({}, Any::class.java)
    private val processor = LedgerPersistenceRequestProcessor(
        currentSandboxGroupContext,
        entitySandboxService,
        delegatedRequestHandlerSelector,
        responseFactory
    )

    override fun processEvent(record: Record<*, *>): List<Record<*, *>> {
        val flowId = requireNotNull((record.key as? ByteArray)?.let(stringDeserializer::deserialize)) {
            "Invalid or missing flow ID"
        }
        val request = requireNotNull((record.value as? ByteArray)?.let(anyDeserializer::deserialize) as? LedgerPersistenceRequest) {
            "Invalid or missing LedgerPersistenceRequest"
        }

        return processor.onNext(singletonList(Record(record.topic, flowId, request)))
    }
}
