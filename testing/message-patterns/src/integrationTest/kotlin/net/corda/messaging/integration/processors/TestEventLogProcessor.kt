package net.corda.messaging.integration.processors

import java.util.concurrent.CountDownLatch
import net.corda.data.demo.DemoRecord
import net.corda.messaging.api.processor.EventLogProcessor
import net.corda.messaging.api.records.EventLogRecord
import net.corda.messaging.api.records.Record
import net.corda.v5.base.util.contextLogger

class TestEventLogProcessor(
    private val latch: CountDownLatch, private val outputTopic: String? = null, private val id: String? = null
) : EventLogProcessor<String, DemoRecord> {
    override val keyClass: Class<String>
        get() = String::class.java
    override val valueClass: Class<DemoRecord>
        get() = DemoRecord::class.java

    private companion object {
        val logder = contextLogger()
    }

    override fun onNext(events: List<EventLogRecord<String, DemoRecord>>): List<Record<*, *>> {
        for (event in events) {
            logder.info("TestEventLogProcessor $id for output topic $outputTopic processing event $event")
            latch.countDown()
        }

        return if (outputTopic != null) {
            listOf(Record(outputTopic, "durableOutputKey", DemoRecord(1)))
        } else {
            emptyList<Record<String, DemoRecord>>()
        }
    }
}
