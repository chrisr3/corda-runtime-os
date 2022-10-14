package net.corda.flow.pipeline.handlers.requests.sessions.service

import java.time.Instant
import net.corda.flow.fiber.FlowIORequest
import net.corda.flow.pipeline.FlowEventContext
import net.corda.flow.pipeline.exceptions.FlowFatalException
import net.corda.flow.pipeline.exceptions.FlowPlatformException
import net.corda.flow.pipeline.exceptions.FlowTransientException
import net.corda.flow.pipeline.sandbox.FlowSandboxService
import net.corda.flow.pipeline.sessions.FlowSessionManager
import net.corda.flow.utils.keyValuePairListOf
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

@Component(service = [InitiateFlowRequestService::class])
class InitiateFlowRequestService @Activate constructor(
    @Reference(service = FlowSessionManager::class)
    private val flowSessionManager: FlowSessionManager,
    @Reference(service = FlowSandboxService::class)
    private val flowSandboxService: FlowSandboxService
) {
    fun getSessionsNotInitiated(
        context: FlowEventContext<Any>,
        sessionToInfo: Set<FlowIORequest.SessionInfo>
    ): Set<FlowIORequest.SessionInfo> {
        val checkpoint = context.checkpoint
        return sessionToInfo.filter { checkpoint.getSessionState(it.sessionId) == null }.toSet()
    }

    fun initiateFlowsNotInitiated(
        context: FlowEventContext<Any>,
        sessionToInfo: Set<FlowIORequest.SessionInfo>,
    ) {
        val sessionsNotInitiated = getSessionsNotInitiated(context, sessionToInfo)
        if (sessionsNotInitiated.isNotEmpty()) {
            initiateFlows(context, sessionsNotInitiated)
        }
    }

    @Suppress("ThrowsCount")
    private fun initiateFlows(
        context: FlowEventContext<Any>,
        sessionsNotInitiated: Set<FlowIORequest.SessionInfo>
    ) {
        val checkpoint = context.checkpoint

        // throw an error if the session already exists (shouldn't really get here for real, but for this class, it's not valid)
        val protocolStore = try {
            flowSandboxService.get(checkpoint.holdingIdentity).protocolStore
        } catch (e: Exception) {
            throw FlowTransientException(
                "Failed to get the flow sandbox for identity ${checkpoint.holdingIdentity}: ${e.message}",
                e
            )
        }

        val flowStack = checkpoint.flowStack
        if (flowStack.isEmpty()) {
            throw FlowFatalException("Flow stack is empty while trying to initiate a flow")
        }

        val initiator = flowStack.nearestFirst { it.isInitiatingFlow }?.flowName
            ?: throw FlowPlatformException("Flow stack did not contain an initiating flow in the stack")

        val (protocolName, protocolVersions) = protocolStore.protocolsForInitiator(initiator, context)

        checkpoint.putSessionStates(
            sessionsNotInitiated.map {
                flowSessionManager.sendInitMessage(
                    checkpoint,
                    it.sessionId,
                    it.counterparty,
                    protocolName,
                    protocolVersions,
                    contextUserProperties = keyValuePairListOf(it.contextUserProperties),
                    contextPlatformProperties = keyValuePairListOf(it.contextPlatformProperties),
                    Instant.now()
                )
            }
        )
    }
}