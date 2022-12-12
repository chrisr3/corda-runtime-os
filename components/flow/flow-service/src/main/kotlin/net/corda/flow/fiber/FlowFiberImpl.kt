package net.corda.flow.fiber

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.FiberScheduler
import co.paralleluniverse.fibers.FiberWriter
import java.io.Serializable
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import net.corda.data.flow.state.checkpoint.FlowStackItem
import net.corda.flow.fiber.FlowFiberImpl.SerializableFiberWriter
import net.corda.utilities.clearMDC
import net.corda.utilities.setMDC
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.debug
import net.corda.v5.base.util.trace
import org.slf4j.Logger

@Suppress("TooManyFunctions")
class FlowFiberImpl(
    override val flowId: UUID,
    override val flowLogic: FlowLogicAndArgs,
    scheduler: FiberScheduler,
) : Fiber<Unit>(flowId.toString(), scheduler), FlowFiber, Interruptable {

    private fun interface SerializableFiberWriter : FiberWriter, Serializable

    companion object {
        private val log: Logger = contextLogger()
    }

    @Transient
    private var flowFiberExecutionContext: FlowFiberExecutionContext? = null

    @Transient
    private var flowCompletion = CompletableFuture<FlowIORequest<*>>()

    @Transient
    var suspensionOutcome: FlowContinuation? = null

    override fun getExecutionContext(): FlowFiberExecutionContext {
        return flowFiberExecutionContext!!
    }

    @Suspendable
    override fun startFlow(flowFiberExecutionContext: FlowFiberExecutionContext): Future<FlowIORequest<*>> {
        this.flowFiberExecutionContext = flowFiberExecutionContext
        log.warn("startFlow flowFiberExecutionContext mdc : ${flowFiberExecutionContext.mdcLoggingData}", CordaRuntimeException("tmp").fillInStackTrace())
        start()
        return flowCompletion
    }

    @Suspendable
    override fun run() {
        try {
            resetLoggingContext("runFlow")
            setCurrentSandboxGroupContext()
            // Ensure run() does not exit via any means without completing the future, in order not to indefinitely block
            // the flow event pipeline. Note that this is executed in a Quasar concurrent executor thread and Throwables are
            // consumed by that too, so if they are rethrown from here we do not get process termination or any other form
            // of critical error handling for free, only undefined behaviour.
            try {
                runFlow()
            } catch (e: FlowContinuationErrorException) {
                // This exception happened because the flow fiber discovered it had failed for some already handled reason
                // outside user code. For example an IO request handler detected some error, but the fiber was being
                // suspended by Corda for the last time to mark it was finished already. Logging the callstack here would be
                // misleading as it would point the log entry to the internal rethrow in Corda. In this case nothing has
                // gone wrong, so we shouldn't log that it has.
                log.warn("Flow was discontinued, reason: ${e.cause?.javaClass?.canonicalName} thrown, ${e.cause?.message}")
                failTopLevelSubFlow(e.cause!!)
            } catch (t: Throwable) {
                log.warn("FlowFiber failed due to Throwable being thrown", t)
                failTopLevelSubFlow(t)
            }

            if (!flowCompletion.isDone) {
                log.warn("runFlow failed to complete normally, forcing a failure")
                failTopLevelSubFlow(IllegalStateException("Flow failed to complete normally, forcing a failure"))
            }
        } finally {
            removeCurrentSandboxGroupContext()
        }
    }

    @Suspendable
    private fun runFlow() {
        initialiseThreadContext()
        log.warn("runFlow flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")
        suspend(FlowIORequest.InitialCheckpoint)

        val outcomeOfFlow = try {
            log.trace { "Flow starting." }
            resetLoggingContext("lorcan - hack")
            log.info("lorcan - sleeping...")
            Thread.sleep(1000)
            log.info("lorcan - sleep over")
            FlowIORequest.FlowFinished(flowLogic.invoke())
        } catch (e: FlowContinuationErrorException) {
            // This was an exception thrown during the processing of the flow pipeline due to something the user code
            // initiated. The user should see the details and point of origin of the 'cause' exception in the log.
            log.warn("Flow failed", e.cause)
            FlowIORequest.FlowFailed(e.cause!!) // cause is not nullable in a FlowContinuationErrorException
        } catch (t: Throwable) {
            // Every other Throwable, including base CordaRuntimeException out of flow user code gets a callstack
            // logged, it is considered an error to allow these to propagate outside the flow.
            log.warn("Flow failed", t)
            FlowIORequest.FlowFailed(t)
        }
        log.info("lorcan - flow invoke complete")

        when (outcomeOfFlow) {
            is FlowIORequest.FlowFinished -> finishTopLevelSubFlow(outcomeOfFlow)
            is FlowIORequest.FlowFailed -> failTopLevelSubFlow(outcomeOfFlow.exception)
            else -> throw IllegalStateException("Unexpected Flow outcome")
        }
    }

    override fun resume(
        flowFiberExecutionContext: FlowFiberExecutionContext,
        suspensionOutcome: FlowContinuation,
        scheduler: FiberScheduler,
    ): Future<FlowIORequest<*>> {
        log.warn("resume lowFiberExecutionContext mdc : ${flowFiberExecutionContext.mdcLoggingData}")

        this.flowFiberExecutionContext = flowFiberExecutionContext
        this.suspensionOutcome = suspensionOutcome
        this.flowCompletion = CompletableFuture<FlowIORequest<*>>()
        resetLoggingContext("resumeunpark1")
        unparkDeserialized(this, scheduler)
        resetLoggingContext("resumeunpark2")
        return flowCompletion
    }

    @Suspendable
    override fun <SUSPENDRETURN> suspend(request: FlowIORequest<SUSPENDRETURN>): SUSPENDRETURN {
        removeCurrentSandboxGroupContext()
        parkAndSerialize(SerializableFiberWriter { _, _ ->
            resetLoggingContext("suspend")
            log.warn("Parking... flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")

            log.trace { "Parking..." }
            val fiberState = getExecutionContext().sandboxGroupContext.checkpointSerializer.serialize(this)
            flowCompletion.complete(FlowIORequest.FlowSuspended(ByteBuffer.wrap(fiberState), request))
            resetLoggingContext("afterPark")
        })

        //log.warn("suspend 1 - resetLoggingContext - flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")
        resetLoggingContext("afterSuspend")
     //   log.warn("suspend 2 - resetLoggingContext - flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")
        setCurrentSandboxGroupContext()

        @Suppress("unchecked_cast")
        return when (val outcome = suspensionOutcome!!) {
            is FlowContinuation.Run -> outcome.value as SUSPENDRETURN
            is FlowContinuation.Error -> throw FlowContinuationErrorException(
                // We populate the container exception message in case user code has a try/catch around the failing statement.
                outcome.exception.message ?: "Unknown error",
                outcome.exception.apply {
                    // If resume occurred in the function which caused a handler to fail, filling in the stack trace here will
                    // identify that function in the log. Out of user code the stack trace is ignored, so we don't need to worry
                    // that it might be filled with less useful information.
                    fillInStackTrace()
                })
            else -> throw IllegalStateException("Tried to return when suspension outcome says to continue")
        }
    }

    @Suspendable
    private fun <T : FlowIORequest<*>> finishTopLevelSubFlow(outcomeOfFlow: T) {
        log.debug { "Flow [$flowId] completed successfully" }
        // We close the sessions here, which delegates to the subFlow finished request handler, rather than combining the logic into the
        // flow finish request handler. This is due to the flow finish code removing the flow's checkpoint, which is needed by the close
        // logic to determine whether all sessions have successfully acknowledged receipt of the close messages.
        val sessions = getRemainingInitiatedSessions()
        if (sessions.isNotEmpty()) {
            suspend(FlowIORequest.SubFlowFinished(sessions))
        }
        flowCompletion.complete(outcomeOfFlow)
    }

    @Suspendable
    private fun failTopLevelSubFlow(throwable: Throwable) {
        // We close the sessions here, which delegates to the subFlow failed request handler, rather than combining the logic into the
        // flow finish request handler. This is due to the flow finish code removing the flow's checkpoint, which is needed by the close
        // logic to determine whether all sessions have successfully acknowledged receipt of the close messages.
        val sessions = getRemainingInitiatedSessions()
        if (sessions.isNotEmpty()) {
            suspend(FlowIORequest.SubFlowFailed(throwable, sessions))
        }
        flowCompletion.complete(FlowIORequest.FlowFailed(throwable))
    }

    private fun getRemainingInitiatedSessions(): List<String> {
        return getRemainingFlowStackItem().sessions.filter { it.initiated }.map { it.sessionId }.toList()
    }

    @Suppress("ThrowsCount")
    private fun getRemainingFlowStackItem(): FlowStackItem {
        val flowStackService = flowFiberExecutionContext?.flowStackService
        return when {
            flowStackService == null -> {
                log.debug { "Flow [$flowId] should have a single flow stack item when finishing but the stack was null" }
                throw CordaRuntimeException("Flow [$flowId] should have a single flow stack item when finishing but the stack was null")
            }
            flowStackService.size > 1 -> {
                log.debug {
                    "Flow [$flowId] should have a single flow stack item when finishing but contained the following elements instead: " +
                            "${flowFiberExecutionContext?.flowStackService}"
                }
                throw CordaRuntimeException(
                    "Flow [$flowId] should have a single flow stack item when finishing but contained " +
                            "${flowFiberExecutionContext?.flowStackService?.size} elements"
                )
            }
            flowStackService.size == 0 -> {
                log.debug { "Flow [$flowId] should have a single flow stack item when finishing but was empty" }
                throw CordaRuntimeException("Flow [$flowId] should have a single flow stack item when finishing but was empty")
            }
            else -> {
                when (val item = flowStackService.peek()) {
                    null -> {
                        log.debug { "Flow [$flowId] should have a single flow stack item when finishing but was empty" }
                        throw CordaRuntimeException("Flow [$flowId] should have a single flow stack item when finishing but was empty")
                    }
                    else -> item
                }
            }
        }
    }

    private fun setCurrentSandboxGroupContext() {
        val context = getExecutionContext()
        context.currentSandboxGroupContext.set(context.sandboxGroupContext)
    }

    private fun removeCurrentSandboxGroupContext() {
        getExecutionContext().currentSandboxGroupContext.remove()
    }

    private fun Throwable.isUnrecoverable(): Boolean = this is VirtualMachineError && this !is StackOverflowError

    private fun initialiseThreadContext() {
        Thread.currentThread().contextClassLoader = flowLogic.javaClass.classLoader
    }

    private fun resetLoggingContext(str: String) {
        //fully clear the fiber before setting the MDC
        log.warn("mdc before reset ($str) flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")
        clearMDC()
        log.warn("mdc mid reset ($str) flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")
        flowFiberExecutionContext?.mdcLoggingData?.let {
            setMDC(it)
        }
        log.warn("mdc after reset ($str) flowFiberExecutionContext mdc : ${flowFiberExecutionContext?.mdcLoggingData}")

    }

    override fun attemptInterrupt() {
        // Contract of Interruptable is that this method should be thread safe, do not call anything here that isn't
        interrupt()
    }
}
