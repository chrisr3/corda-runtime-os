package net.corda.components.examples.bootstrap.topics

import com.typesafe.config.ConfigFactory
import net.corda.libs.configuration.write.CordaConfigurationKey
import net.corda.libs.configuration.write.CordaConfigurationVersion
import net.corda.libs.configuration.write.factory.CordaWriteServiceFactory
import net.corda.libs.kafka.topic.utils.factory.TopicUtilsFactory
import net.corda.lifecycle.LifeCycle
import net.corda.lifecycle.LifeCycleCoordinator
import net.corda.lifecycle.StartEvent
import org.osgi.service.component.annotations.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.util.*

@Component
class BootstrapTopics(
    private val lifeCycleCoordinator: LifeCycleCoordinator,
    private val topicUtilsFactory: TopicUtilsFactory,
    private val cordaWriteServiceFactory: CordaWriteServiceFactory,
    private val topicPrefix: String,
) : LifeCycle {
    private companion object {
        private val log: Logger = LoggerFactory.getLogger(this::class.java)
        const val topicName = "configTopic"
        const val kafkaProperty: String = "bootstrap.servers=localhost:9092"
    }


    override var isRunning: Boolean = false

    override fun start() {
        isRunning = true
        val kafkaProps = Properties()
        kafkaProps.load(StringReader(kafkaProperty))

        val topicUtils = topicUtilsFactory.createTopicUtils(kafkaProps)
        topicUtils.createTopic(topicPrefix + topicName, 1, 1)
        val configuration = ConfigFactory.load("config1")
        val packageVersion = CordaConfigurationVersion("corda", 1, 0)
        val componentVersion = CordaConfigurationVersion("corda", 1, 0)
        val configurationKey = CordaConfigurationKey("corda", packageVersion, componentVersion)
        val configurationWriteService = cordaWriteServiceFactory.createWriteService(topicPrefix + topicName)

        configurationWriteService.updateConfiguration(configurationKey, configuration)
        isRunning = false
        lifeCycleCoordinator.postEvent(StartEvent)
    }

    override fun stop() {
        isRunning = false
        log.info("Stopping topic bootstrapper")
    }
}