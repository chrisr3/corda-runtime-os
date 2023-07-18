package net.corda.flow.mapper.impl

import net.corda.avro.serialization.CordaAvroSerializationFactory
import net.corda.data.flow.event.SessionEvent
import net.corda.data.flow.event.StartFlow
import net.corda.data.flow.event.mapper.ExecuteCleanup
import net.corda.data.flow.event.mapper.FlowMapperEvent
import net.corda.data.flow.event.mapper.ScheduleCleanup
import net.corda.data.flow.event.session.SessionData
import net.corda.data.flow.event.session.SessionError
import net.corda.data.flow.event.session.SessionInit
import net.corda.data.flow.state.mapper.FlowMapperState
import net.corda.flow.mapper.executor.FlowMapperEventExecutor
import net.corda.flow.mapper.factory.FlowMapperEventExecutorFactory
import net.corda.flow.mapper.impl.executor.ExecuteCleanupEventExecutor
import net.corda.flow.mapper.impl.executor.ScheduleCleanupEventExecutor
import net.corda.flow.mapper.impl.executor.SessionErrorExecutor
import net.corda.flow.mapper.impl.executor.SessionEventExecutor
import net.corda.flow.mapper.impl.executor.SessionInitExecutor
import net.corda.flow.mapper.impl.executor.StartFlowExecutor
import net.corda.flow.mapper.impl.executor.generateAppMessage
import net.corda.libs.configuration.SmartConfig
import net.corda.schema.Schemas.Flow.*
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.LoggerFactory
import java.time.Instant

@Component(service = [FlowMapperEventExecutorFactory::class])
class FlowMapperEventExecutorFactoryImpl @Activate constructor(
    @Reference(service = CordaAvroSerializationFactory::class)
    private val cordaAvroSerializationFactory: CordaAvroSerializationFactory
) : FlowMapperEventExecutorFactory {

    private val sessionEventSerializer = cordaAvroSerializationFactory.createAvroSerializer<SessionEvent> { }

    private companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)
    }
    override fun create(
        eventKey: String,
        flowMapperEvent: FlowMapperEvent,
        state: FlowMapperState?,
        flowConfig: SmartConfig,
        instant: Instant
    ): FlowMapperEventExecutor {
        return when (val flowMapperEventPayload = flowMapperEvent.payload) {
            is SessionEvent -> {
                logger.info("Processing ${(flowMapperEvent.payload as SessionEvent).messageDirection} session event: ${flowMapperEventPayload.payload::class.java}...")

                when (val sessionPayload = flowMapperEventPayload.payload) {
                    is SessionInit -> {
                        SessionInitExecutor(
                            eventKey,
                            flowMapperEventPayload,
                            sessionPayload,
                            state,
                            sessionEventSerializer,
                            flowConfig
                        )
                    }
                    is SessionError -> {
                        SessionErrorExecutor(
                            eventKey,
                            flowMapperEventPayload,
                            state,
                            instant,
                            sessionEventSerializer,
                            ::generateAppMessage,
                            flowConfig
                        )
                    }
                    else -> {
                        if ((sessionPayload as SessionData).sessionInit != null) {
                            logger.info("handling data init")
                            SessionInitExecutor(
                                eventKey,
                                flowMapperEventPayload,
                                sessionPayload.sessionInit,
                                state,
                                sessionEventSerializer,
                                flowConfig
                            )
                        } else {
                            SessionEventExecutor(
                                eventKey,
                                flowMapperEventPayload,
                                state,
                                instant,
                                sessionEventSerializer,
                                ::generateAppMessage,
                                flowConfig
                            )
                        }
                    }
                }
            }
            is StartFlow -> StartFlowExecutor(eventKey, FLOW_EVENT_TOPIC, flowMapperEventPayload, state)
            is ExecuteCleanup -> ExecuteCleanupEventExecutor(eventKey)
            is ScheduleCleanup -> ScheduleCleanupEventExecutor(eventKey, flowMapperEventPayload, state)

            else -> throw NotImplementedError(
                "The event type '${flowMapperEventPayload.javaClass.name}' is not supported."
            )
        }
    }
}
