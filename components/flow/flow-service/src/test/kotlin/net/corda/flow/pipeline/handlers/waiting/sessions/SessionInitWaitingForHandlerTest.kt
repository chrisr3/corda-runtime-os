package net.corda.flow.pipeline.handlers.waiting.sessions

import net.corda.data.flow.event.SessionEvent
import net.corda.data.flow.event.session.SessionData
import net.corda.data.flow.event.session.SessionInit
import net.corda.data.flow.state.session.SessionState
import net.corda.flow.fiber.FlowContinuation
import net.corda.flow.pipeline.exceptions.FlowFatalException
import net.corda.flow.pipeline.handlers.waiting.SessionInitWaitingForHandler
import net.corda.flow.pipeline.handlers.waiting.WaitingForSessionInit
import net.corda.flow.state.FlowCheckpoint
import net.corda.flow.test.utils.buildFlowEventContext
import net.corda.session.manager.SessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer

class SessionInitWaitingForHandlerTest {

    private companion object {
        const val SESSION_ID = "session id"
    }

    private val checkpoint = mock<FlowCheckpoint>()
    private val sessionState = SessionState()
    private val sessionManager = mock<SessionManager>()
    private val sessionInitWaitingForHandler = SessionInitWaitingForHandler(sessionManager)

    @Suppress("Unused")
    @BeforeEach
    fun setup() {
        sessionState.sessionId = SESSION_ID

        whenever(checkpoint.getSessionState(sessionState.sessionId)).thenReturn(sessionState)
    }

    @Test
    fun `Returns FlowContinuation#Run after receiving next session init event`() {
        val sessionEvent = SessionEvent().apply {
            sessionId = SESSION_ID
            payload = SessionInit()
            sequenceNum = 1
        }

        whenever(sessionManager.getNextReceivedEvent(sessionState)).thenReturn(sessionEvent)

        val inputContext = buildFlowEventContext(
            checkpoint = checkpoint,
            inputEventPayload = sessionEvent
        )

        val continuation = sessionInitWaitingForHandler.runOrContinue(inputContext, WaitingForSessionInit(SESSION_ID))

        verify(sessionManager).acknowledgeReceivedEvent(any(), any())
        assertEquals(FlowContinuation.Run(Unit), continuation)
    }

    @Test
    fun `Returns FlowContinuation#Run after receiving next session data event`() {
        val sessionEvent = SessionEvent().apply {
            sessionId = SESSION_ID
            payload = SessionData(ByteBuffer.allocate(1), SessionInit())
            sequenceNum = 1
        }

        whenever(sessionManager.getNextReceivedEvent(sessionState)).thenReturn(sessionEvent)

        val inputContext = buildFlowEventContext(
            checkpoint = checkpoint,
            inputEventPayload = sessionEvent
        )

        val continuation = sessionInitWaitingForHandler.runOrContinue(inputContext, WaitingForSessionInit(SESSION_ID))

        verify(sessionManager, times(0)).acknowledgeReceivedEvent(any(), any())
        assertEquals(FlowContinuation.Run(Unit), continuation)
    }

    @Test
    fun `Throws an exception if the session being waited for does not exist in the checkpoint`() {
        val inputContext = buildFlowEventContext(
            checkpoint = checkpoint,
            inputEventPayload = Unit
        )
        assertThrows<FlowFatalException> {
            sessionInitWaitingForHandler.runOrContinue(inputContext, WaitingForSessionInit(SESSION_ID))
        }
    }

    @Test
    fun `Throws an exception if no session event is received`() {
        whenever(sessionManager.getNextReceivedEvent(sessionState)).thenReturn(null)

        val inputContext = buildFlowEventContext(
            checkpoint = checkpoint,
            inputEventPayload = Unit
        )
        assertThrows<FlowFatalException> {
            sessionInitWaitingForHandler.runOrContinue(inputContext, WaitingForSessionInit(SESSION_ID))
        }
    }
}