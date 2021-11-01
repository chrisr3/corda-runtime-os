package net.corda.components.session.manager.dedup

import net.corda.data.session.RequestWindow
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.records.Record
import net.corda.messaging.api.subscription.listener.StateAndEventListener
import net.corda.v5.base.util.contextLogger
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DedupListener(
    dedupState: DedupState
) : StateAndEventListener<String, RequestWindow> {

    private val eventTopic: String = dedupState.eventTopic
    private val publisher: Publisher = dedupState.publisher
    private val executorService: ScheduledExecutorService = dedupState.executorService
    private val scheduledTasks: MutableMap<String, ScheduledFuture<*>> = dedupState.scheduledTasks

    companion object {
        private val log = contextLogger()
    }

    override fun onPartitionSynced(states: Map<String, RequestWindow>) {
        log.info("Synced states $states")
        val currentTime = System.currentTimeMillis()
        for (stateEntry in states) {
            val key = stateEntry.key
            val state = stateEntry.value
            val expiryTime = state.expireTime
            if (currentTime > expiryTime) {
                log.info("Clearing up expired state for synced key $key")
                publisher.publish(listOf(Record(eventTopic, key, null)))
            } else {
                val scheduleTask = executorService.schedule(
                    {
                        log.info("Clearing up expired state for key $key")
                        publisher.publish(listOf(Record(eventTopic, key, null)))
                    },
                    expiryTime - currentTime,
                    TimeUnit.MILLISECONDS
                )
                scheduledTasks[key] = scheduleTask
            }
        }
    }

    override fun onPartitionLost(states: Map<String, RequestWindow>) {
        log.info("Lost partition states $states")
        for (stateEntry in states) {
            val key = stateEntry.key
            scheduledTasks.remove(key)?.cancel(true)
        }
    }

    override fun onPostCommit(updatedStates: Map<String, RequestWindow?>) {
        log.info("Committed states $updatedStates")
    }
}
