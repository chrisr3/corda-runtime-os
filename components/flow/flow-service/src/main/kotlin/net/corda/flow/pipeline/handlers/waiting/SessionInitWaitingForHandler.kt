package net.corda.flow.pipeline.handlers.waiting

import net.corda.data.flow.event.session.SessionInit
import net.corda.flow.fiber.FlowContinuation
import net.corda.flow.pipeline.events.FlowEventContext
import net.corda.flow.pipeline.exceptions.FlowFatalException
import net.corda.session.manager.SessionManager
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

data class WaitingForSessionInit(val sessionId: String)

@Component(service = [FlowWaitingForHandler::class])
class SessionInitWaitingForHandler @Activate constructor(
    @Reference(service = SessionManager::class)
    private val sessionManager: SessionManager
) : FlowWaitingForHandler<WaitingForSessionInit> {
    override val type = WaitingForSessionInit::class.java

    override fun runOrContinue(context: FlowEventContext<*>, waitingFor: WaitingForSessionInit): FlowContinuation {
        val checkpoint = context.checkpoint

        val sessionState = checkpoint.getSessionState(waitingFor.sessionId)
            ?: throw FlowFatalException(
                "Session: ${waitingFor.sessionId} doesn't exist even though it should be created by session event pre-processing",
            )

        val eventToAcknowledgeProcessingOf = sessionManager.getNextReceivedEvent(sessionState)
            ?: throw FlowFatalException(
                "Session: ${waitingFor.sessionId} has no event to acknowledge even though it should be received by session event " +
                        "pre-processing",
            )

        //need to clear SessionInit from received events. SessionData with SessionInit field is handled in WaitingForData handler
        if (eventToAcknowledgeProcessingOf.payload is SessionInit) {
            sessionManager.acknowledgeReceivedEvent(sessionState, eventToAcknowledgeProcessingOf.sequenceNum)
        }

        return FlowContinuation.Run(Unit)
    }
}
