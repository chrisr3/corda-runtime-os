package net.corda.messagebus.db.consumer

import net.corda.avro.serialization.CordaAvroDeserializer
import net.corda.messagebus.api.CordaTopicPartition
import net.corda.messagebus.api.consumer.CordaConsumer
import net.corda.messagebus.api.consumer.CordaConsumerRebalanceListener
import net.corda.messagebus.api.consumer.CordaConsumerRecord
import net.corda.messagebus.api.consumer.CordaOffsetResetStrategy
import net.corda.messagebus.db.configuration.ResolvedConsumerConfig
import net.corda.messagebus.db.datamodel.CommittedPositionEntry
import net.corda.messagebus.db.datamodel.TransactionState
import net.corda.messagebus.db.persistence.DBAccess
import net.corda.messagebus.db.persistence.DBAccess.Companion.ATOMIC_TRANSACTION
import net.corda.messagebus.db.serialization.MessageHeaderSerializer
import net.corda.messaging.api.exception.CordaMessageAPIFatalException
import net.corda.messaging.api.exception.CordaMessageAPIIntermittentException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Suppress("TooManyFunctions", "LongParameterList")
internal class DBCordaConsumerImpl<K : Any, V : Any> constructor(
    consumerConfig: ResolvedConsumerConfig,
    private val dbAccess: DBAccess,
    private val consumerGroup: ConsumerGroup,
    private val keyDeserializer: CordaAvroDeserializer<K>,
    private val valueDeserializer: CordaAvroDeserializer<V>,
    private var defaultListener: CordaConsumerRebalanceListener?,
    private var headerSerializer: MessageHeaderSerializer
) : CordaConsumer<K, V> {

    companion object {
        /** Minimal period between checking for new records in database */
        private val MIN_POLL_PERIOD = Duration.ofMillis(100)
        /** Time of the last check for new records in database */
        @Volatile
        private var lastPollTime: Instant = Instant.MIN
        /** Offset positions retrieved with the last check for new records in database */
        private val lastOffsetPositions = mutableMapOf<CordaTopicPartition, Long>()
        /** Lock for updating [lastPollTime] and [lastOffsetPositions] */
        private val pollLock = ReentrantLock()
    }

    enum class SubscriptionType { NONE, ASSIGNED, SUBSCRIBED }


    // Also used by the consumer group
    internal val clientId = consumerConfig.clientId
    private val log: Logger = LoggerFactory.getLogger(clientId)

    private val groupId = consumerConfig.group
    private val maxPollRecords = consumerConfig.maxPollSize
    private val autoResetStrategy = consumerConfig.offsetResetStrategy
    private var subscriptionType = SubscriptionType.NONE

    private var topicPartitions = emptySet<CordaTopicPartition>()
    private var currentTopicPartition = topicPartitions.iterator()
    private val pausedPartitions = mutableSetOf<CordaTopicPartition>()
    private val partitionListeners = mutableMapOf<String, CordaConsumerRebalanceListener?>()
    private val lastReadOffset = mutableMapOf<CordaTopicPartition, Long>()

    override fun subscribe(topics: Collection<String>, listener: CordaConsumerRebalanceListener?) {
        checkNotAssigned()
        consumerGroup.subscribe(this, topics)
        topics.forEach { partitionListeners[it] = listener }
        subscriptionType = SubscriptionType.SUBSCRIBED
    }

    override fun subscribe(topic: String, listener: CordaConsumerRebalanceListener?) {
        subscribe(listOf(topic), listener)
    }

    override fun assign(partitions: Collection<CordaTopicPartition>) {
        checkNotSubscribed()
        topicPartitions = partitions.toSet()
        dbAccess.getMaxCommittedPositions(groupId, topicPartitions).forEach { (topicPartition, offset) ->
            lastReadOffset[topicPartition] = offset ?: getAutoResetOffset(topicPartition)
        }
        subscriptionType = SubscriptionType.ASSIGNED
    }

    override fun assignment(): Set<CordaTopicPartition> {
        return topicPartitions
    }

    private fun getAutoResetOffset(partition: CordaTopicPartition): Long {
        return when (autoResetStrategy) {
            CordaOffsetResetStrategy.EARLIEST -> beginningOffsets(setOf(partition)).values.single()
            CordaOffsetResetStrategy.LATEST -> endOffsets(setOf(partition)).values.single()
            else -> throw CordaMessageAPIFatalException("No offset for $partition")
        }
    }

    override fun position(partition: CordaTopicPartition): Long {
        return lastReadOffset[partition] ?: getAutoResetOffset(partition)
    }

    override fun seek(partition: CordaTopicPartition, offset: Long) {
        if (lastReadOffset.containsKey(partition)) {
            lastReadOffset[partition] = offset
        } else {
            throw CordaMessageAPIIntermittentException("Partition is not currently assigned to consumer $clientId")
        }
    }

    override fun seekToBeginning(partitions: Collection<CordaTopicPartition>) {
        beginningOffsets(partitions).forEach { (partition, offset) -> seek(partition, offset) }
    }

    override fun seekToEnd(partitions: Collection<CordaTopicPartition>) {
        endOffsets(partitions).forEach { (partition, offset) -> seek(partition, offset) }
    }

    override fun beginningOffsets(partitions: Collection<CordaTopicPartition>): Map<CordaTopicPartition, Long> {
        return dbAccess.getEarliestRecordOffset(partitions.toSet())
    }

    override fun endOffsets(partitions: Collection<CordaTopicPartition>): Map<CordaTopicPartition, Long> {
        return dbAccess.getLatestRecordOffset(partitions.toSet())
    }

    override fun resume(partitions: Collection<CordaTopicPartition>) {
        pausedPartitions.removeAll(partitions.toSet())
    }

    override fun pause(partitions: Collection<CordaTopicPartition>) {
        pausedPartitions.addAll(partitions)
    }

    override fun paused(): Set<CordaTopicPartition> {
        return pausedPartitions
    }

    /**
     * Returns offset position for given [topicPartition] that was retrieved with the latest database poll ensuring
     * that the last database poll is not older than [MIN_POLL_PERIOD].
     */
    private fun getLastOffsetPosition(topicPartition: CordaTopicPartition): Long {
        pollLock.withLock {
            if (Duration.between(lastPollTime, Instant.now()) >= MIN_POLL_PERIOD) {
                lastOffsetPositions.putAll(dbAccess.getLatestRecordOffsets())
                lastPollTime = Instant.now()
            }
            return lastOffsetPositions[topicPartition] ?: -1
        }
    }

    /**
     * Checks whether [topicPartition] has records with offset that is equal or greater than [fromOffset]. Check is
     * done against values retrieved with the latest database poll, that could be up to [MIN_POLL_PERIOD] old.
     */
    private fun recordsAvailable(fromOffset: Long, topicPartition: CordaTopicPartition, timeout: Duration): Boolean {
        if (getLastOffsetPosition(topicPartition) >= fromOffset) return true
        if (timeout <= Duration.ZERO) return false
        Thread.sleep(timeout.toMillis())
        return getLastOffsetPosition(topicPartition) >= fromOffset
    }

    override fun poll(timeout: Duration): List<CordaConsumerRecord<K, V>> {
        val topicPartition = getNextTopicPartition() ?: return emptyList()
        val fromOffset = position(topicPartition)

        if (!recordsAvailable(fromOffset, topicPartition, timeout)) return emptyList()

        var latestOffset = fromOffset
        val dbRecords = dbAccess.readRecords(fromOffset, topicPartition, maxPollRecords)
            .takeWhile { it.transactionId.state != TransactionState.PENDING && it.recordOffset == latestOffset++ }
            .filter { it.transactionId.state != TransactionState.ABORTED }

        val result = dbRecords.mapNotNull { dbRecord ->
            val deserializedValue = deserializeValue(dbRecord.value)
            val isDeserialized = deserializedValue != null || dbRecord.value == null

            if (isDeserialized) {
                CordaConsumerRecord(
                    dbRecord.topic,
                    dbRecord.partition,
                    dbRecord.recordOffset,
                    deserializeKey(dbRecord.key),
                    deserializedValue,
                    dbRecord.timestamp.toEpochMilli(),
                    headerSerializer.deserialize(dbRecord.headers ?: "{}")
                )
            } else {
                null
            }
        }

        if (dbRecords.isNotEmpty()) {
            seek(topicPartition, dbRecords.last().recordOffset + 1)
        }
        return result
    }

    override fun resetToLastCommittedPositions(offsetStrategy: CordaOffsetResetStrategy) {
        val lastCommittedOffsets = dbAccess.getMaxCommittedPositions(groupId, topicPartitions)
        for (topicPartition in topicPartitions) {
            lastReadOffset[topicPartition] = lastCommittedOffsets[topicPartition]
                ?: getAutoResetOffset(topicPartition)
        }
    }

    override fun commitSyncOffsets(event: CordaConsumerRecord<K, V>, metaData: String?) {
        dbAccess.writeOffsets(
            listOf(
                CommittedPositionEntry(
                    event.topic,
                    groupId,
                    event.partition,
                    event.offset,
                    ATOMIC_TRANSACTION,
                )
            )
        )
    }

    override fun getPartitions(topic: String): List<CordaTopicPartition> {
        return dbAccess.getTopicPartitionMapFor(topic).toList()
    }

    @Synchronized
    override fun close() {
        log.info("Closing consumer $clientId")
        consumerGroup.unsubscribe(this)
        updateTopicPartitions() // Will trigger the callback for removed topic partitions
        dbAccess.close()
    }

    override fun setDefaultRebalanceListener(defaultListener: CordaConsumerRebalanceListener) {
        this.defaultListener = defaultListener
    }

    internal fun getConsumerGroup(): String = groupId

    /**
     * Query the [ConsumerGroup] to get the latest set of topic partitions.  New or
     * removed topic partitions should be signaled to any listener appropriately.
     */
    private fun updateTopicPartitions() {
        // Only valid for SUBSCRIBED consumers
        if (subscriptionType == SubscriptionType.ASSIGNED) {
            return
        }

        val newTopicPartitions = consumerGroup.getTopicPartitionsFor(this)
        val addedTopicPartitions = newTopicPartitions - topicPartitions
        val removedTopicPartitions = topicPartitions - newTopicPartitions

        if (addedTopicPartitions.isEmpty() && removedTopicPartitions.isEmpty()) {
            return
        }

        removedTopicPartitions.groupBy { it.topic }.forEach { (topic, removedPartitions) ->
            removedPartitions.forEach { lastReadOffset.remove(it) }
            revokePartitions(topic, removedPartitions)
        }

        addedTopicPartitions.groupBy { it.topic }.forEach { (topic, newPartitions) ->
            dbAccess.getMaxCommittedPositions(groupId, newPartitions.toSet()).forEach { (topicPartition, offset) ->
                lastReadOffset[topicPartition] = offset ?: getAutoResetOffset(topicPartition)
            }
            assignPartitions(topic, newPartitions)
        }

        topicPartitions = newTopicPartitions
    }

    private fun revokePartitions(topic: String, removedPartitions: Collection<CordaTopicPartition>) {
        partitionListeners[topic]?.onPartitionsRevoked(removedPartitions)
            ?: defaultListener?.onPartitionsRevoked(removedPartitions)
    }

    private fun assignPartitions(topic: String, newPartitions: Collection<CordaTopicPartition>) {
        partitionListeners[topic]?.onPartitionsAssigned(newPartitions)
            ?: defaultListener?.onPartitionsAssigned(newPartitions)
    }

    /**
     * Returns null when all partitions are paused
     *
     * Internal for testing
     */
    internal fun getNextTopicPartition(): CordaTopicPartition? {
        updateTopicPartitions()

        if (topicPartitions.isEmpty()) {
            return null
        }

        @Synchronized
        fun nextPartition(): CordaTopicPartition {
            return if (!currentTopicPartition.hasNext()) {
                currentTopicPartition = topicPartitions.iterator()
                currentTopicPartition.next()
            } else {
                currentTopicPartition.next()
            }
        }

        val first = nextPartition()
        var next = first

        while (pausedPartitions.contains(next)) {
            next = nextPartition()
            if (first == next) {
                return null
            }
        }
        return next
    }

    private fun deserializeKey(bytes: ByteArray): K {
        return keyDeserializer.deserialize(bytes)
            ?: throw CordaMessageAPIFatalException("Should never get null result from key deserialize")
    }

    private fun deserializeValue(bytes: ByteArray?): V? {
        return if (bytes != null) {
            valueDeserializer.deserialize(bytes)
        } else {
            null
        }
    }

    private fun checkNotSubscribed() {
        if (subscriptionType == SubscriptionType.SUBSCRIBED) {
            throw CordaMessageAPIFatalException("Cannot assign when consumer is already subscribed to topic(s)")
        }
    }

    private fun checkNotAssigned() {
        if (subscriptionType == SubscriptionType.ASSIGNED) {
            throw CordaMessageAPIFatalException("Cannot subscribed when consumer is already assigned topic partitions")
        }
    }
}
