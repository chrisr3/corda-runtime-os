package net.corda.uniqueness.checker.impl.fake

import net.corda.data.uniqueness.UniquenessCheckExternalRequest
import net.corda.data.uniqueness.UniquenessCheckExternalResponse
import net.corda.data.uniqueness.UniquenessCheckExternalResultInputStateConflict
import net.corda.data.uniqueness.UniquenessCheckExternalResultInputStateUnknown
import net.corda.data.uniqueness.UniquenessCheckExternalResultMalformedRequest
import net.corda.data.uniqueness.UniquenessCheckExternalResultReferenceStateConflict
import net.corda.data.uniqueness.UniquenessCheckExternalResultReferenceStateUnknown
import net.corda.data.uniqueness.UniquenessCheckExternalResultSuccess
import net.corda.data.uniqueness.UniquenessCheckExternalResultTimeWindowOutOfBounds
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.lifecycle.createCoordinator
import net.corda.uniqueness.checker.UniquenessChecker
import net.corda.utilities.time.Clock
import net.corda.utilities.time.UTCClock
import net.corda.v5.base.util.contextLogger
import net.corda.v5.serialization.SingletonSerializeAsToken
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.osgi.service.component.propertytypes.ServiceRanking
import org.slf4j.Logger
import java.time.Instant

/**
 * An in-memory implementation of the uniqueness checker component, which does not persist any data,
 * and therefore loses any history of previous requests when a class instance is destroyed.
 *
 * Intended to be used as a fake for testing purposes only - DO NOT USE ON A REAL SYSTEM
 */
@ServiceRanking(Int.MAX_VALUE)
@Component(service = [UniquenessChecker::class])
class UniquenessCheckerImplFake(
    coordinatorFactory: LifecycleCoordinatorFactory,
    private val clock: Clock
) : UniquenessChecker, SingletonSerializeAsToken {

    @Activate
    constructor(
        @Reference(service = LifecycleCoordinatorFactory::class)
        coordinatorFactory: LifecycleCoordinatorFactory
    ) : this(coordinatorFactory, UTCClock())

    companion object {
        private val log: Logger = contextLogger()
    }

    private val responseCache = HashMap<String, UniquenessCheckExternalResponse>()

    // Value of state cache is populated with the consuming tx id when spent, null if unspent
    private val stateCache = HashMap<String, String?>()

    private val lifecycleCoordinator: LifecycleCoordinator = coordinatorFactory
        .createCoordinator<UniquenessChecker>(::eventHandler)

    override val isRunning: Boolean
        get() = lifecycleCoordinator.isRunning

    override fun start() {
        log.info("Uniqueness checker starting.")
        lifecycleCoordinator.start()
    }

    override fun stop() {
        log.info("Uniqueness checker stopping.")
        lifecycleCoordinator.stop()
    }

    @Synchronized
    @Suppress("ComplexMethod", "LongMethod")
    override fun processRequests(
        requests: List<UniquenessCheckExternalRequest>
    ): List<UniquenessCheckExternalResponse> {
        return requests.map { request ->
            responseCache[request.txId] ?: run {
                val (knownInputStates, unknownInputStates) =
                    request.inputStates.partition { stateCache.containsKey(it) }
                val (knownReferenceStates, unknownReferenceStates) =
                    request.referenceStates.partition { stateCache.containsKey(it) }
                val inputStateConflicts = knownInputStates
                    .filter { stateCache[it] != null && stateCache[it] != request.txId }
                val referenceStateConflicts = knownReferenceStates
                    .filter { stateCache[it] != null && stateCache[it] != request.txId }
                val timeWindowEvaluationTime = clock.instant()

                val response = when {
                    request.numOutputStates < 0 -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultMalformedRequest(
                                "Number of output states cannot be less than 0."
                            )
                        )
                    }

                    unknownInputStates.isNotEmpty() -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultInputStateUnknown(unknownInputStates)
                        )
                    }

                    unknownReferenceStates.isNotEmpty() -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultReferenceStateUnknown(unknownReferenceStates)
                        )
                    }

                    inputStateConflicts.isNotEmpty() -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultInputStateConflict(inputStateConflicts)
                        )
                    }

                    referenceStateConflicts.isNotEmpty() -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultReferenceStateConflict(referenceStateConflicts)
                        )
                    }

                    !isTimeWindowValid(
                        timeWindowEvaluationTime,
                        request.timeWindowLowerBound,
                        request.timeWindowUpperBound
                    ) -> {
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultTimeWindowOutOfBounds(
                                timeWindowEvaluationTime,
                                request.timeWindowLowerBound,
                                request.timeWindowUpperBound
                            )
                        )
                    }

                    else -> {
                        // Write unspent states
                        repeat(request.numOutputStates) {
                            stateCache["${request.txId}:$it"] = null
                        }
                        // Write spent states - overwrites any earlier entries for unspent states
                        stateCache.putAll(request.inputStates.associateWith { request.txId })
                        UniquenessCheckExternalResponse(
                            request.txId,
                            UniquenessCheckExternalResultSuccess(clock.instant())
                        )
                    }
                }

                responseCache[request.txId] = response
                response
            }
        }
    }

    private fun isTimeWindowValid(
        currentTime: Instant,
        timeWindowLowerBound: Instant?,
        timeWindowUpperBound: Instant
    ): Boolean {
        return (
            (timeWindowLowerBound == null || !timeWindowLowerBound.isAfter(currentTime)) &&
                timeWindowUpperBound.isAfter(currentTime)
            )
    }

    private fun eventHandler(event: LifecycleEvent, coordinator: LifecycleCoordinator) {
        log.info("Uniqueness checker received event $event.")
        when (event) {
            is StartEvent -> {
                log.info("Uniqueness checker is UP")
                coordinator.updateStatus(LifecycleStatus.UP)
            }
            is StopEvent -> {
                log.info("Uniqueness checker is DOWN")
                coordinator.updateStatus(LifecycleStatus.DOWN)
            }
            is RegistrationStatusChangeEvent -> {
                log.info("Uniqueness checker is ${event.status}")
                coordinator.updateStatus(event.status)
            }
            else -> {
                log.warn("Unexpected event $event!")
            }
        }
    }
}
