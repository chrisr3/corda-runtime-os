package net.corda.flow.pipeline.impl

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import net.corda.data.flow.FlowKey
import net.corda.data.flow.FlowStartContext
import net.corda.data.flow.event.StartFlow
import net.corda.data.flow.event.mapper.FlowMapperEvent
import net.corda.data.flow.event.mapper.ScheduleCleanup
import net.corda.data.flow.output.FlowStates
import net.corda.data.flow.output.FlowStatus
import net.corda.data.flow.state.checkpoint.Checkpoint
import net.corda.data.flow.state.external.ExternalEventState
import net.corda.data.flow.state.session.SessionState
import net.corda.data.flow.state.session.SessionStateType
import net.corda.data.identity.HoldingIdentity
import net.corda.flow.fiber.cache.FlowFiberCache
import net.corda.flow.pipeline.converters.FlowEventContextConverter
import net.corda.flow.pipeline.events.FlowEventContext
import net.corda.flow.pipeline.exceptions.FlowMarkedForKillException
import net.corda.flow.pipeline.factory.FlowMessageFactory
import net.corda.flow.pipeline.factory.FlowRecordFactory
import net.corda.flow.pipeline.sessions.FlowSessionManager
import net.corda.flow.state.FlowCheckpoint
import net.corda.flow.test.utils.buildFlowEventContext
import net.corda.libs.configuration.SmartConfigFactory
import net.corda.messaging.api.processor.StateAndEventProcessor
import net.corda.messaging.api.records.Record
import net.corda.schema.configuration.FlowConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FlowToBeKilledExceptionProcessingTest {

    private companion object {
        const val SESSION_ID_1 = "s1"
        const val SESSION_ID_2 = "s2"
        const val SESSION_ID_3 = "s3"
        const val SESSION_ID_4 = "s4"
        const val SESSION_ID_5 = "s5"
        const val FLOW_ID = "flowId"
    }

    private val flowRecordFactory = mock<FlowRecordFactory>()
    private val flowMessageFactory = mock<FlowMessageFactory>()
    private val flowEventContextConverter = mock<FlowEventContextConverter>()
    private val flowSessionManager = mock<FlowSessionManager>()
    private val flowConfig = ConfigFactory.empty().withValue(
        FlowConfig.PROCESSING_MAX_RETRY_WINDOW_DURATION, ConfigValueFactory.fromAnyRef(20000L)
    )
    private val smartFlowConfig = SmartConfigFactory.createWithoutSecurityServices().create(flowConfig)

    // starting session states
    private val sessionState1 = createSessionState(SESSION_ID_1, true, SessionStateType.CLOSING)
    private val sessionState2 = createSessionState(SESSION_ID_2, true, SessionStateType.ERROR)
    private val sessionState3 = createSessionState(SESSION_ID_3, true, SessionStateType.CLOSED)
    private val sessionState4 = createSessionState(SESSION_ID_4, false, SessionStateType.CONFIRMED)
    private val sessionState5 = createSessionState(SESSION_ID_5, false, SessionStateType.CREATED)

    // session states which have had their state updated to ERROR
    private val erroredSessionState1 = createSessionState(SESSION_ID_1, false, SessionStateType.ERROR)
    private val erroredSessionState4 = createSessionState(SESSION_ID_4, false, SessionStateType.ERROR)
    private val erroredSessionState5 = createSessionState(SESSION_ID_5, false, SessionStateType.ERROR)

    private val allFlowSessions = listOf(sessionState1, sessionState2, sessionState3, sessionState4, sessionState5)
    private val activeFlowSessionIds = listOf(SESSION_ID_1, SESSION_ID_4, SESSION_ID_5)
    private val erroredFlowSessions = listOf(erroredSessionState1, erroredSessionState4, erroredSessionState5)
    private val allFlowSessionsAfterErrorsSent = listOf(
        erroredSessionState1, sessionState2, sessionState3, erroredSessionState4, erroredSessionState5
    )

    private fun createSessionState(sessionId: String, hasScheduledCleanup: Boolean, status: SessionStateType): SessionState =
        SessionState().apply {
            this.sessionId = sessionId
            this.hasScheduledCleanup = hasScheduledCleanup
            this.status = status
        }

    private val scheduleCleanupRecord4 = Record("t", SESSION_ID_4, FlowMapperEvent(ScheduleCleanup(1000)))
    private val scheduleCleanupRecord5 = Record("t", SESSION_ID_5, FlowMapperEvent(ScheduleCleanup(1000)))
    private val flowKey = FlowKey("id", HoldingIdentity("x500", "grp1"))
    private val checkpoint = mock<FlowCheckpoint> {
        whenever(it.flowKey).thenReturn(flowKey)
    }
    private val flowKilledStatus = FlowStatus().apply {
        key = checkpoint.flowKey
        flowId = checkpoint.flowId
        flowStatus = FlowStates.KILLED
        processingTerminatedReason = "reason"
    }
    private val flowKilledStatusRecord = Record("s", flowKey, flowKilledStatus)
    private val mockResponse = mock<StateAndEventProcessor.Response<Checkpoint>>()
    private val flowFiberCache = mock<FlowFiberCache>()

    private val target = FlowEventExceptionProcessorImpl(
        flowMessageFactory,
        flowRecordFactory,
        flowEventContextConverter,
        flowSessionManager,
        flowFiberCache
    )

    @BeforeEach
    fun setup() {
        target.configure(smartFlowConfig)
        whenever(checkpoint.flowId).thenReturn(FLOW_ID)
    }

    @Test
    fun `processing MarkedForKillException sends error events to all sessions then schedules cleanup for any not yet scheduled`() {
        val testContext = buildFlowEventContext(checkpoint, Any())
        val exception = FlowMarkedForKillException("reasoning")
        val contextCapture = argumentCaptor<FlowEventContext<*>>()

        whenever(checkpoint.doesExist).thenReturn(true)

        // The first call returns all sessions.
        whenever(checkpoint.sessions)
            .thenReturn(allFlowSessions)
            .thenReturn(allFlowSessionsAfterErrorsSent)

        // we send error messages for all active flow sessions, returning the flow sessions with state updated to ERROR
        whenever(flowSessionManager.sendErrorMessages(eq(checkpoint), eq(activeFlowSessionIds), eq(exception), any()))
            .thenReturn(erroredFlowSessions)

        // we clean up all flow sessions that have not already been cleaned up
        whenever(flowRecordFactory.createFlowMapperEventRecord(eq(SESSION_ID_4), any())).thenReturn(scheduleCleanupRecord4)
        whenever(flowRecordFactory.createFlowMapperEventRecord(eq(SESSION_ID_5), any())).thenReturn(scheduleCleanupRecord5)

        // callouts to factories to create flow killed status record
        whenever(flowMessageFactory.createFlowKilledStatusMessage(any(), any())).thenReturn(flowKilledStatus)
        whenever(flowRecordFactory.createFlowStatusRecord(flowKilledStatus)).thenReturn(flowKilledStatusRecord)

        whenever(flowEventContextConverter.convert(contextCapture.capture())).thenReturn(mockResponse)

        val response = target.process(exception, testContext)

        verify(checkpoint, times(1)).putSessionStates(erroredFlowSessions)
        verify(checkpoint, times(1)).markDeleted()

        assertThat(response).isEqualTo(mockResponse)

        val killContext = contextCapture.firstValue
        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should contain cleanup records for sessions that aren't already scheduled")
            .contains(scheduleCleanupRecord4, scheduleCleanupRecord5)

        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should contain the flow killed status record")
            .contains(flowKilledStatusRecord)

        val updatedFlowSessions = killContext.checkpoint.sessions.associateBy { it.sessionId }
        assertThat(updatedFlowSessions.values.all { it.hasScheduledCleanup })
            .withFailMessage("All flow sessions should now be marked as scheduled for cleanup")
            .isTrue
        assertThat(updatedFlowSessions[SESSION_ID_1]?.status).isEqualTo(SessionStateType.ERROR)
        assertThat(updatedFlowSessions[SESSION_ID_2]?.status).isEqualTo(SessionStateType.ERROR)
        assertThat(updatedFlowSessions[SESSION_ID_3]?.status).isEqualTo(SessionStateType.CLOSED)
        assertThat(updatedFlowSessions[SESSION_ID_4]?.status).isEqualTo(SessionStateType.ERROR)
        assertThat(updatedFlowSessions[SESSION_ID_5]?.status).isEqualTo(SessionStateType.ERROR)
    }

    @Test
    fun `processing MarkedForKillException removes retries and external events from output records`() {
        whenever(checkpoint.doesExist).thenReturn(true)

        val externalEventState = ExternalEventState()
        whenever(checkpoint.externalEventState).thenReturn(externalEventState)
        whenever(checkpoint.inRetryState).thenReturn(true)

        val testContext = buildFlowEventContext(checkpoint, Any())
        val exception = FlowMarkedForKillException("reasoning")
        val contextCapture = argumentCaptor<FlowEventContext<*>>()

        whenever(checkpoint.sessions)
            .thenReturn(emptyList())
            .thenReturn(emptyList())

        // callouts to factories to create flow killed status record
        whenever(flowMessageFactory.createFlowKilledStatusMessage(any(), any())).thenReturn(flowKilledStatus)
        whenever(flowRecordFactory.createFlowStatusRecord(flowKilledStatus)).thenReturn(flowKilledStatusRecord)

        whenever(flowEventContextConverter.convert(contextCapture.capture())).thenReturn(mockResponse)

        val response = target.process(exception, testContext)

        verify(checkpoint, times(1)).markDeleted()

        assertThat(response).isEqualTo(mockResponse)

        val killContext = contextCapture.firstValue
        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should only have flow status record")
            .hasSize(1)

        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should contain the flow killed status record")
            .contains(flowKilledStatusRecord)
    }

    @Test
    fun `processing MarkedForKillException when checkpoint does not exist only outputs flow killed status record`() {
        whenever(checkpoint.doesExist).thenReturn(false)

        val inputEventPayload = StartFlow(FlowStartContext().apply {statusKey = flowKey}, "")

        val testContext = buildFlowEventContext(checkpoint, inputEventPayload)
        val exception = FlowMarkedForKillException("reasoning")
        val contextCapture = argumentCaptor<FlowEventContext<*>>()

        whenever(flowRecordFactory.createFlowStatusRecord(any())).thenReturn(flowKilledStatusRecord)

        whenever(flowEventContextConverter.convert(contextCapture.capture())).thenReturn(mockResponse)

        val response = target.process(exception, testContext)

        assertThat(response).isEqualTo(mockResponse)

        val killContext = contextCapture.firstValue
        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should only have flow status record")
            .hasSize(1)

        assertThat(killContext.outputRecords)
            .withFailMessage("Output records should contain the flow killed status record")
            .contains(flowKilledStatusRecord)
    }

    @Test
    fun `error processing MarkedForKillException falls back to null state record, empty response events and marked for DLQ`() {
        val testContext = buildFlowEventContext(checkpoint, Any())
        val exception = FlowMarkedForKillException("reasoning")
        val fallbackFailureResponse = StateAndEventProcessor.Response(
            null, emptyList(), true
        )

        whenever(checkpoint.doesExist).thenReturn(true)

        // The first call returns all sessions.
        whenever(checkpoint.sessions)
            .thenReturn(allFlowSessions)
            .thenReturn(allFlowSessionsAfterErrorsSent)

        // simulating exception thrown during processing of the flow session
        whenever(flowSessionManager.sendErrorMessages(eq(checkpoint), eq(activeFlowSessionIds), eq(exception), any()))
            .thenThrow(IllegalArgumentException("some error message while sending errors to peers"))

        val response = target.process(exception, testContext)

        assertThat(response).isEqualTo(fallbackFailureResponse)
    }
}
