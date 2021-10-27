package net.corda.lifecycle.domino.logic

import com.typesafe.config.Config
import net.corda.configuration.read.ConfigurationHandler
import net.corda.lifecycle.ErrorEvent
import net.corda.lifecycle.Lifecycle
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.LifecycleEventHandler
import net.corda.lifecycle.LifecycleException
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationHandle
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.lifecycle.domino.logic.util.ResourcesHolder
import net.corda.v5.base.util.contextLogger
import java.lang.IllegalArgumentException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@Suppress("TooManyFunctions")
open class DominoTile private constructor(
    instanceName: String,
    coordinatorFactory: LifecycleCoordinatorFactory,
    val createResources: ((resources: ResourcesHolder) -> Any)? = null,
    private val children: Collection<DominoTile> = emptySet(),
    private val configurationChangeHandler: ConfigurationChangeHandler<*>? = null,
) : Lifecycle {

    constructor(instanceName: String,
                coordinatorFactory: LifecycleCoordinatorFactory,
                createResources: ((resources: ResourcesHolder) -> Any)? = null,
                children: Collection<DominoTile> = emptySet()
    ): this(instanceName, coordinatorFactory, createResources, children, configurationChangeHandler = null)

    constructor(instanceName: String,
                coordinatorFactory: LifecycleCoordinatorFactory,
                configurationChangeHandler: ConfigurationChangeHandler<*>,
                createResources: ((resources: ResourcesHolder) -> Any)? = null,
                children: Collection<DominoTile> = emptySet()
    ): this(instanceName, coordinatorFactory, createResources, children, configurationChangeHandler)

    companion object {
        private val logger = contextLogger()
        private val instancesIndex = ConcurrentHashMap<String, Int>()
    }
    private object StartTile : LifecycleEvent
    private data class StopTile(val dueToError: Boolean) : LifecycleEvent
    private class ConfigUpdate(val callback: () -> Any) : LifecycleEvent
    private class ConfigApplied(val configUpdateResult: ConfigUpdateResult) : LifecycleEvent
    private class ResourcesCreated: LifecycleEvent
    enum class State {
        Created,
        Started,
        StoppedDueToError,
        StoppedDueToBadConfig,
        StoppedByParent
    }

    private val name = LifecycleCoordinatorName(
        instanceName,
        instancesIndex.compute(instanceName) { _, last ->
            if (last == null) {
                1
            } else {
                last + 1
            }
        }.toString()
    )

    private val controlLock = ReentrantReadWriteLock()

    final override fun start() {
        logger.info("Starting $name")
        coordinator.start()
        coordinator.postEvent(StartTile)
    }

    final override fun stop() {
        if (state != State.StoppedByParent) {
            coordinator.postEvent(StopTile(false))
        }
    }

    fun <T> withLifecycleLock(access: () -> T): T {
        return controlLock.read {
            access.invoke()
        }
    }

    private val coordinator = coordinatorFactory.createCoordinator(name, EventHandler())
    internal val resources = ResourcesHolder()
    internal val configResources = ResourcesHolder()

    @Volatile
    private var registrations: Collection<RegistrationHandle>? = null

    private val currentState = AtomicReference(State.Created)

    private val isOpen = AtomicBoolean(true)

    @Volatile
    private var configReady = configurationChangeHandler == null
    @Volatile
    private var resourcesReady = createResources == null
    @Volatile
    private var configRegistration : AutoCloseable? = null

    sealed class ConfigUpdateResult{
        object Success: ConfigUpdateResult()
        object NoUpdate: ConfigUpdateResult()
        data class Error(val e: Throwable): ConfigUpdateResult()
    }


    fun configApplied(configUpdateResult: ConfigUpdateResult) {
        coordinator.postEvent(ConfigApplied(configUpdateResult))
    }

    fun resourcesStarted(withErrors: Boolean) {
        if (withErrors) {
            gotError(RuntimeException())
        } else {
            coordinator.postEvent(ResourcesCreated())
        }
    }

    private inner class Handler<C>(val configurationChangeHandler: ConfigurationChangeHandler<C>) : ConfigurationHandler {

        @Volatile
        private var lastConfiguration: C? = null

        private fun applyNewConfiguration(newConfiguration: Config) {
            val configuration = configurationChangeHandler.configFactory(newConfiguration)
            logger.info("Got configuration $name")
            if (configuration == lastConfiguration) {
                logger.info("Configuration had not changed $name")
                configApplied(ConfigUpdateResult.NoUpdate)
            } else {
                configurationChangeHandler.applyNewConfiguration(configuration, lastConfiguration, configResources)
                lastConfiguration = configuration
                logger.info("Reconfigured $name")
            }
        }

        override fun onNewConfiguration(changedKeys: Set<String>, config: Map<String, Config>) {
            if (changedKeys.contains(configurationChangeHandler.key)) {
                val newConfiguration = config[configurationChangeHandler.key]
                if (newConfiguration != null) {
                    configUpdateFromCoordinator {
                        applyNewConfiguration(newConfiguration)
                    }
                }
            }
        }
    }

    val state: State
        get() = currentState.get()

    override val isRunning: Boolean
        get() = state == State.Started

    private fun updateState(newState: State) {
        val oldState = currentState.getAndSet(newState)
        if (newState != oldState) {
            val status = when (newState) {
                State.Started -> LifecycleStatus.UP
                State.StoppedByParent, State.StoppedDueToBadConfig -> LifecycleStatus.DOWN
                State.StoppedDueToError -> LifecycleStatus.ERROR
                State.Created -> null
            }
            controlLock.write {
                status?.let { coordinator.updateStatus(it) }
            }
            logger.info("State of $name is $newState")
        }
    }

    private fun configUpdateFromCoordinator(callback: () -> Any) {
        coordinator.postEvent(ConfigUpdate(callback))
    }

    private inner class EventHandler : LifecycleEventHandler {
        private fun handleControlEvent(event: LifecycleEvent) {
            when (event) {
                is ErrorEvent -> {
                    gotError(event.cause)
                }
                is StartEvent -> {
                    // Do nothing
                }
                is StopEvent -> {
                    // Do nothing
                }
                is StopTile -> {
                    if (state != State.StoppedByParent) {
                        stopTile()
                        if (event.dueToError) {
                            updateState(State.StoppedDueToError)
                        } else {
                            updateState(State.StoppedByParent)
                        }
                    }
                }
                is StartTile -> {
                    when (state) {
                        State.Created, State.StoppedByParent -> readyOrStartTile()
                        State.Started -> {} // Do nothing
                        State.StoppedDueToError -> logger.warn("Can not start $name, it was stopped due to an error")
                        State.StoppedDueToBadConfig -> logger.warn("Can not start $name, it was stopped due to bad config")
                    }
                }
                is ConfigUpdate -> {
                    when(state) {
                        State.StoppedDueToError -> { }
                        State.Started, State.StoppedDueToBadConfig, State.Created, State.StoppedByParent -> { event.callback.invoke() }
                    }
                }
                is RegistrationStatusChangeEvent -> {
                    if (event.status == LifecycleStatus.UP) {
                        logger.info("RegistrationStatusChangeEvent $name going up.")
                        readyOrStartTile()
                    } else {
                        val errorKids = children.filter { it.state == State.StoppedDueToError || it.state == State.StoppedDueToBadConfig }
                        if (errorKids.isEmpty()) {
                            stop()
                        } else {
                            gotError(Exception("$name Had error in ${errorKids.map { it.name }}"))
                        }
                    }
                }
                is ResourcesCreated -> {
                    resourcesReady = true
                    // check if children are started, config has been applied etc. and signal UP, if so.
                    when (state) {
                        State.StoppedDueToBadConfig, State.Created, State.StoppedByParent  -> {
                            logger.info("Resource ready for $name.")
                            readyOrStartTile()
                        }
                        State.StoppedDueToError, State.Started -> {} // Do nothing
                    }
                }
                is ConfigApplied -> {
                    when(event.configUpdateResult) {
                        ConfigUpdateResult.Success -> {
                            configReady = true
                            when (state) {
                                // check if children are started, config has been applied etc. and signal UP, if so.
                                State.StoppedDueToBadConfig, State.Created, State.StoppedByParent -> {
                                    logger.info("Config ready for $name.")
                                    readyOrStartTile()
                                }
                                State.StoppedDueToError, State.Started -> {} // Do nothing
                            }
                        }
                        is ConfigUpdateResult.Error -> {
                            stopTile(false)
                            updateState(State.StoppedDueToBadConfig)
                        }
                        ConfigUpdateResult.NoUpdate -> {
                            logger.info("No Config update for $name.")
                        }
                    }
                }
                else -> logger.warn("Unexpected event $event")
            }
        }

        override fun processEvent(event: LifecycleEvent, coordinator: LifecycleCoordinator) {
            if (!isOpen.get()) {
                return
            }

            controlLock.write {
                handleControlEvent(event)
            }
        }
    }

    internal fun gotError(cause: Throwable) {
        logger.warn("Got error in $name", cause)
        if (state != State.StoppedDueToError) {
            coordinator.postEvent(StopTile(true))
        }
    }

    private fun readyOrStartTile() {
        if (registrations == null) {
            registrations = children.map {
                it.name
            }.map {
                coordinator.followStatusChangesByName(setOf(it))
            }
            logger.info("Created $name with ${children.map { it.name }}")
        }
        startKidsIfNeeded()
    }


    private fun startKidsIfNeeded() {
        if (children.map { it.state }.contains(State.StoppedDueToError) || children.map { it.state }.contains(State.StoppedDueToBadConfig) ) {
            children.filter {
                !(it.state == State.StoppedDueToError || it.state == State.StoppedDueToBadConfig)
            }.forEach {
                it.stop()
            }
        } else {
            children.forEach {
                it.start()
            }

            if (children.all { it.isRunning }) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    createResourcesAndStart()
                } catch (e: Throwable) {
                    gotError(e)
                }
            } else {
                logger.info("Not all child tiles started yet.\n Started Children = ${children.filter{ it.isRunning }}\n Not Started Children = ${children.filter { !it.isRunning }}.")
            }
        }
    }

    private fun createResourcesAndStart() {
        resources.close()
        createResources?.invoke(resources)
        if (configRegistration == null && configurationChangeHandler != null) {
            logger.info("Registering for Config Updates $name.")
            configRegistration = configurationChangeHandler.configurationReaderService.registerForUpdates(Handler(configurationChangeHandler))
        }
        if (resourcesReady && configReady) {
            updateState(State.Started)
        }
    }

    private fun stopTile(stopConfigListener: Boolean = true) {
        resources.close()
        resourcesReady = (createResources == null)
        configResources.close()

        if (stopConfigListener) {
            configRegistration?.close()
            if (configRegistration != null) logger.info("Unregistered for Config Updates $name.")
            configRegistration = null
        }
        configReady = (configurationChangeHandler == null)
        
        children.forEach {
            if (!(it.state == State.StoppedDueToError || it.state == State.StoppedDueToBadConfig)) {
                it.stop()
            }
        }
    }

    override fun close() {
        registrations?.forEach {
            it.close()
        }
        configRegistration?.close()
        configRegistration = null
        configResources.close()
        controlLock.write {
            isOpen.set(false)

            stopTile()

            try {
                coordinator.close()
            } catch (e: LifecycleException) {
                // This try-catch should be removed once CORE-2786 is fixed
                logger.debug("Could not close coordinator", e)
            }
        }
        children.reversed().forEach {
            @Suppress("TooGenericExceptionCaught")
            try {
                it.close()
            } catch (e: Throwable) {
                logger.warn("Could not close $it", e)
            }
        }
    }

    override fun toString(): String {
        return "$name: $state: $children"
    }
}