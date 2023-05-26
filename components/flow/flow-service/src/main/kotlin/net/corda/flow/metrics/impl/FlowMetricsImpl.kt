package net.corda.flow.metrics.impl

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.data.flow.output.FlowStates
import net.corda.flow.metrics.FlowMetricsRecorder
import net.corda.flow.pipeline.metrics.FlowMetrics
import net.corda.flow.state.FlowCheckpoint
import net.corda.utilities.time.Clock

class FlowMetricsImpl(
    private val clock: Clock,
    private val flowMetricsRecorder: FlowMetricsRecorder,
    private val flowCheckpoint: FlowCheckpoint,
    private val recordTimestamp: Long
) : FlowMetrics {

    private val currentState: FlowMetricState
    private val eventReceivedTimestampMillis: Long
    private var fiberStartTime = clock.nowInMillis()
    private var fiberExecutionTime: Long = 0
    private var sequenceNumberCache = mutableMapOf<String, MutableSet<Long?>>()

    private companion object {
        val objectMapper = ObjectMapper()
    }

    init {
        eventReceivedTimestampMillis = clock.nowInMillis()

        currentState = objectMapper.readValue(
            flowCheckpoint.flowMetricsState,
            FlowMetricState::class.java
        )
    }

    override fun flowEventReceived(flowEventType: String){
        // Record the event lag
        flowMetricsRecorder.recordFlowEventLag(eventReceivedTimestampMillis - recordTimestamp, flowEventType)
    }

    override fun flowStarted() {
        currentState.flowProcessingStartTime = clock.nowInMillis()

        flowCheckpoint.flowStartContext.createdTimestamp?.let {
            flowMetricsRecorder.recordFlowStartLag(clock.nowInMillis() - it.toEpochMilli())
        }
    }

    override fun flowFiberEntered() {
        fiberStartTime = clock.nowInMillis()

        // If we were waiting on a suspension then record the wait time.
        if (currentState.suspensionAction != null && currentState.suspensionTimestampMillis != null) {
            val flowSuspensionTime = clock.nowInMillis() - currentState.suspensionTimestampMillis!!
            flowMetricsRecorder.recordFlowSuspensionCompletion(currentState.suspensionAction!!, flowSuspensionTime)
            currentState.totalSuspensionTime += flowSuspensionTime
            currentState.suspensionAction = null
            currentState.suspensionTimestampMillis = null
        }
    }

    override fun flowFiberExited() {
        fiberExecutionTime = clock.nowInMillis() - fiberStartTime
        flowMetricsRecorder.recordFiberExecution(fiberExecutionTime)
        currentState.totalFiberExecutionTime += fiberExecutionTime
        currentState.suspensionCount++
        currentState.suspensionAction = null
        currentState.suspensionTimestampMillis = null
    }

    override fun flowFiberExitedWithSuspension(suspensionOperationName: String?) {
        flowFiberExited()
        currentState.suspensionAction = suspensionOperationName
        currentState.suspensionTimestampMillis = clock.nowInMillis()
    }

    override fun flowEventCompleted(flowEventType: String) {
        val pipelineExecutionTime = clock.instant().toEpochMilli() - eventReceivedTimestampMillis
        flowMetricsRecorder.recordPipelineExecution(pipelineExecutionTime, flowEventType)
        currentState.totalPipelineExecutionTime += pipelineExecutionTime
        flowCheckpoint.setMetricsState(objectMapper.writeValueAsString(currentState))
    }

    override fun flowCompletedSuccessfully() {
        recordFlowCompleted(FlowStates.COMPLETED.toString())
    }

    override fun flowFailed() {
        recordFlowCompleted(FlowStates.FAILED.toString())
    }

    override fun flowSessionMessageSent(flowEventType: String, sessionId: String, sequenceNumber: Long?) {
        val sessionMetricState = currentState.sessionMetricStateBySessionId.computeIfAbsent(sessionId) {
            SessionMetricState()
        }
        flowMetricsRecorder.recordFlowSessionMessagesSent(flowEventType)

        if (isReplay(sequenceNumber, sessionMetricState)) {
            flowMetricsRecorder.recordFlowSessionMessagesReplayed(flowEventType)
        } else if (isAckOrError(sequenceNumber)) {
            //ignore
        } else {
            val cache = sequenceNumberCache.computeIfAbsent(sessionId) {
                mutableSetOf()
            }
            cache.add(sequenceNumber)
            val incrementedHighestContiguousSequenceNumber = sessionMetricState.highestContiguousSequenceNumber + 1

            for(seqNum in cache) {
                when {
                    cache.contains(incrementedHighestContiguousSequenceNumber) -> {
                        cache.remove(incrementedHighestContiguousSequenceNumber)
                        sessionMetricState.highestContiguousSequenceNumber++
                    }
                }
            }

        }
    }

    private fun isAckOrError(sequenceNumber: Long?) : Boolean {
        return (sequenceNumber == null || sequenceNumber == 0L)
    }

    private fun isReplay (sequenceNumber: Long?, sessionMetricState: SessionMetricState) : Boolean{
        return when {
            isAckOrError(sequenceNumber) -> false
            sequenceNumber!! <= sessionMetricState.highestContiguousSequenceNumber -> false
            else -> false
        }
    }

    override fun flowSessionMessageReceived(flowEventType: String) {
        flowMetricsRecorder.recordFlowSessionMessagesReceived(flowEventType)
    }

    private fun recordFlowCompleted(completionStatus: String) {
        val flowCompletionTime = clock.instant().toEpochMilli() - currentState.flowProcessingStartTime
        flowMetricsRecorder.recordFlowCompletion(flowCompletionTime, completionStatus)
        flowMetricsRecorder.recordTotalSuspensionTime(currentState.totalSuspensionTime)
        flowMetricsRecorder.recordTotalFiberExecutionTime(currentState.totalFiberExecutionTime)
        flowMetricsRecorder.recordTotalPipelineExecutionTime(currentState.totalPipelineExecutionTime)
    }

    private fun Clock.nowInMillis(): Long {
        return this.instant().toEpochMilli()
    }

    private class FlowMetricState {
        var flowProcessingStartTime: Long = 0
        var suspensionTimestampMillis: Long? = null
        var suspensionAction: String? = null
        var suspensionCount: Long = 0
        var totalSuspensionTime: Long = 0
        var totalFiberExecutionTime: Long = 0
        var totalPipelineExecutionTime: Long = 0
        var sessionMetricStateBySessionId: MutableMap<String, SessionMetricState> = mutableMapOf()
    }

    private class SessionMetricState {
        var highestContiguousSequenceNumber: Long = 0
    }
}