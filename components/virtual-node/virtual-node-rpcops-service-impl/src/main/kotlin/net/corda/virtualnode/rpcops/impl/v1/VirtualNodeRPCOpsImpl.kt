package net.corda.virtualnode.rpcops.impl.v1

import java.time.Duration
import java.time.Instant
import java.util.UUID
import net.corda.configuration.read.ConfigChangedEvent
import net.corda.configuration.read.ConfigurationReadService
import net.corda.cpiinfo.read.CpiInfoReadService
import net.corda.data.ExceptionEnvelope
import net.corda.data.virtualnode.AsynchronousOperationState
import net.corda.data.virtualnode.VirtualNodeOperationStatus as AvroVirtualNodeOperationStatus
import net.corda.data.virtualnode.VirtualNodeAsynchronousRequest
import net.corda.data.virtualnode.VirtualNodeCpiUpgradeRequest
import net.corda.data.virtualnode.VirtualNodeCreateRequest
import net.corda.data.virtualnode.VirtualNodeCreateResponse
import net.corda.data.virtualnode.VirtualNodeManagementRequest
import net.corda.data.virtualnode.VirtualNodeManagementResponse
import net.corda.data.virtualnode.VirtualNodeManagementResponseFailure
import net.corda.data.virtualnode.VirtualNodeOperationType
import net.corda.data.virtualnode.VirtualNodeUpgradeStatus
import net.corda.httprpc.PluggableRPCOps
import net.corda.httprpc.asynchronous.v1.AsyncError
import net.corda.httprpc.asynchronous.v1.AsyncOperationState
import net.corda.httprpc.asynchronous.v1.AsyncOperationStatus
import net.corda.httprpc.asynchronous.v1.AsyncResponse
import net.corda.httprpc.exception.InvalidInputDataException
import net.corda.httprpc.exception.ResourceNotFoundException
import net.corda.httprpc.response.ResponseEntity
import net.corda.httprpc.security.CURRENT_RPC_CONTEXT
import net.corda.libs.configuration.helper.getConfig
import net.corda.libs.cpiupload.endpoints.v1.CpiIdentifier
import net.corda.libs.virtualnode.endpoints.v1.VirtualNodeRPCOps
import net.corda.libs.virtualnode.endpoints.v1.types.VirtualNodeInfo
import net.corda.libs.virtualnode.endpoints.v1.types.VirtualNodeRequest
import net.corda.libs.virtualnode.endpoints.v1.types.VirtualNodes
import net.corda.lifecycle.DependentComponents
import net.corda.lifecycle.Lifecycle
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.schema.configuration.ConfigKeys
import net.corda.utilities.time.ClockFactory
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.debug
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import net.corda.virtualnode.rpcops.common.VirtualNodeSender
import net.corda.virtualnode.rpcops.common.VirtualNodeSenderFactory
import net.corda.virtualnode.rpcops.impl.v1.ExceptionTranslator.Companion.translate
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.virtualnode.operation.read.VirtualNodeOperationStatusReadService
import net.corda.virtualnode.rpcops.impl.v1.types.VirtualNodeUpgradeOperationStatus
import net.corda.virtualnode.rpcops.impl.validation.VirtualNodeValidationService
import net.corda.libs.virtualnode.endpoints.v1.types.HoldingIdentity as HoldingIdentityEndpointType

@Component(service = [PluggableRPCOps::class])
internal class VirtualNodeRPCOpsImpl @Activate constructor(
    @Reference(service = LifecycleCoordinatorFactory::class)
    coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = ConfigurationReadService::class)
    configurationReadService: ConfigurationReadService,
    @Reference(service = VirtualNodeInfoReadService::class)
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
    @Reference(service = VirtualNodeSenderFactory::class)
    private val virtualNodeSenderFactory: VirtualNodeSenderFactory,
    @Reference(service = ClockFactory::class)
    private var clockFactory: ClockFactory,
    @Reference(service = CpiInfoReadService::class)
    private val cpiInfoReadService: CpiInfoReadService,
    @Reference(service = PublisherFactory::class)
    private val publisherFactory: PublisherFactory,
    @Reference(service = VirtualNodeOperationStatusReadService::class)
    private val virtualNodeOperationStatusReadService: VirtualNodeOperationStatusReadService,
) : VirtualNodeRPCOps, PluggableRPCOps<VirtualNodeRPCOps>, Lifecycle {

    private companion object {
        private val requiredKeys = setOf(ConfigKeys.MESSAGING_CONFIG, ConfigKeys.RPC_CONFIG)
        val logger = contextLogger()

        private const val REGISTRATION = "REGISTRATION"
        private const val SENDER = "SENDER"
        private const val CONFIG_HANDLE = "CONFIG_HANDLE"
        private const val VIRTUAL_NODE_ASYNC_OPERATION_CLIENT_ID = "VIRTUAL_NODE_ASYNC_OPERATION_CLIENT"
    }

    private val clock = clockFactory.createUTCClock()

    // Http RPC values
    override val targetInterface: Class<VirtualNodeRPCOps> = VirtualNodeRPCOps::class.java
    override val protocolVersion = 1

    private val virtualNodeValidationService = VirtualNodeValidationService(
        virtualNodeInfoReadService,
        cpiInfoReadService
    )

    // Lifecycle
    private val dependentComponents = DependentComponents.of(::virtualNodeInfoReadService)
    private val lifecycleCoordinator = coordinatorFactory.createCoordinator(
        LifecycleCoordinatorName.forComponent<VirtualNodeRPCOps>()
    ) { event: LifecycleEvent, coordinator: LifecycleCoordinator ->
        when (event) {
            is StartEvent -> {
                configurationReadService.start()
                coordinator.createManagedResource(REGISTRATION) {
                    coordinator.followStatusChangesByName(
                        setOf(
                            LifecycleCoordinatorName.forComponent<ConfigurationReadService>()
                        )
                    )
                }
                dependentComponents.registerAndStartAll(coordinator)
                coordinator.updateStatus(LifecycleStatus.UP)
                logger.info("${this::javaClass.name} is now Up")
            }
            is StopEvent -> coordinator.updateStatus(LifecycleStatus.DOWN)
            is RegistrationStatusChangeEvent -> {
                when (event.status) {
                    LifecycleStatus.ERROR -> {
                        coordinator.closeManagedResources(setOf(CONFIG_HANDLE))
                        coordinator.postEvent(StopEvent(errored = true))
                    }
                    LifecycleStatus.UP -> {
                        // Receive updates to the RPC and Messaging config
                        coordinator.createManagedResource(CONFIG_HANDLE) {
                            configurationReadService.registerComponentForUpdates(
                                coordinator,
                                requiredKeys
                            )
                        }
                    }
                    else -> logger.debug { "Unexpected status: ${event.status}" }
                }
                coordinator.updateStatus(event.status)
                logger.info("${this::javaClass.name} is now ${event.status}")
            }
            is ConfigChangedEvent -> {
                if (requiredKeys.all { it in event.config.keys } and event.keys.any { it in requiredKeys }) {
                    val rpcConfig = event.config.getConfig(ConfigKeys.RPC_CONFIG)
                    val messagingConfig = event.config.getConfig(ConfigKeys.MESSAGING_CONFIG)
                    val duration = Duration.ofMillis(rpcConfig.getInt(ConfigKeys.RPC_ENDPOINT_TIMEOUT_MILLIS).toLong())
                    // Make sender unavailable while we're updating
                    coordinator.updateStatus(LifecycleStatus.DOWN)
                    coordinator.createManagedResource(SENDER) {
                        virtualNodeSenderFactory.createSender(
                            duration,
                            messagingConfig,
                            PublisherConfig(VIRTUAL_NODE_ASYNC_OPERATION_CLIENT_ID),
                        )
                    }
//                    publisherFactory.createPublisher(
//                        PublisherConfig(VIRTUAL_NODE_UPGRADE_CLIENT_ID),
//                        messagingConfig
//                    )
                    coordinator.updateStatus(LifecycleStatus.UP)
                }
            }
        }
    }

    /**
     * Sends the [request] to the configuration management topic on bus.
     *
     * @property request is a [VirtualNodeManagementRequest]. This an enveloper around the intended request
     * @throws CordaRuntimeException If the message could not be published.
     * @return [VirtualNodeManagementResponse] which is an envelope around the actual response.
     *  This response corresponds to the [VirtualNodeManagementRequest] received by the function
     * @see VirtualNodeManagementRequest
     * @see VirtualNodeManagementResponse
     */
    private fun sendAndReceive(request: VirtualNodeManagementRequest): VirtualNodeManagementResponse {
        if (!isRunning) throw IllegalStateException(
            "${this.javaClass.simpleName} is not running! Its status is: ${lifecycleCoordinator.status}"
        )

        val sender = lifecycleCoordinator.getManagedResource<VirtualNodeSender>(SENDER)
            ?: throw IllegalStateException("Sender not initialized, check component status for ${this.javaClass.name}")

        return sender.sendAndReceive(request)
    }

    private fun sendAsync(key: String, request: VirtualNodeAsynchronousRequest) {
        if (!isRunning) throw IllegalStateException(
            "${this.javaClass.simpleName} is not running! Its status is: ${lifecycleCoordinator.status}"
        )

        val sender = lifecycleCoordinator.getManagedResource<VirtualNodeSender>(SENDER)
            ?: throw IllegalStateException("Sender not initialized, check component status for ${this.javaClass.name}")

        return sender.sendAsync(key, request)
    }

    /**
     * Retrieves the list of virtual nodes stored on the message bus
     *
     * @throws IllegalStateException is thrown if the component isn't running and therefore able to service requests.
     * @return [VirtualNodes] which is a list of [VirtualNodeInfo]
     *
     * @see VirtualNodes
     * @see VirtualNodeInfo
     */
    override fun getAllVirtualNodes(): VirtualNodes {
        if (!isRunning) throw IllegalStateException(
            "${this.javaClass.simpleName} is not running! Its status is: ${lifecycleCoordinator.status}"
        )
        return VirtualNodes(virtualNodeInfoReadService.getAll().map { it.toEndpointType() })
    }

    override fun upgradeCpi(virtualNodeShortId: String, cpiFileChecksum: String): ResponseEntity<AsyncResponse> {
        virtualNodeValidationService.validateVirtualNodeExists(virtualNodeShortId)
        val upgradeCpi = virtualNodeValidationService.validateAndGetUpgradeCpi(cpiFileChecksum)
        val currentCpi = checkNotNull(cpiInfoReadService.get(upgradeCpi.cpiId)) {
            "CPI with identifier ${upgradeCpi.cpiId} was not found in CPI cache."
        }
        virtualNodeValidationService.validateCpiUpgradePrerequisites(currentCpi, upgradeCpi)

        val requestId = sendAsynchronousRequest(Instant.now(), virtualNodeShortId, cpiFileChecksum, CURRENT_RPC_CONTEXT.get().principal)

        return ResponseEntity.accepted(AsyncResponse(requestId))
    }

    override fun operationStatus(requestId: String): ResponseEntity<AsyncOperationStatus> {
        val avroStatus = virtualNodeOperationStatusReadService.getByRequestId(requestId)
            ?: throw ResourceNotFoundException("Virtual Node Operation Status", requestId)
        return createAsyncOperationResponse(avroStatus)
    }

    private fun createAsyncOperationResponse(avroStatus: AvroVirtualNodeOperationStatus): ResponseEntity<AsyncOperationStatus> {
        val status = when (avroStatus.operationType) {
            VirtualNodeOperationType.VIRTUAL_NODE_UPGRADE_CPI -> {
                val opData = avroStatus.operationData as VirtualNodeUpgradeOperationStatus
                AsyncOperationStatus.ok(
                    "UPGRADE_VIRTUAL_NODE",
                    VirtualNodeUpgradeOperationStatus(
                        opData.requestId,
                        opData.originalCpiFileChecksum,
                        opData.targetCpiFileChecksum,
                        opData.virtualNodeShortHash,
                        opData.actor,
                        opData.stage
                    ),
                    avroStatus.state.toEndpointType(),
                    avroStatus.requestTimestamp,
                    avroStatus.completedTimestamp,
                    avroStatus.errors.map{it.toEndpointType()},
                    )
            }
            VirtualNodeOperationType.VIRTUAL_NODE_CREATION -> {
                throw CordaRuntimeException("Virtual node async operation type not recognized: ${avroStatus.operationType}")
            }
            else -> {
                throw CordaRuntimeException("Virtual node async operation type not recognized: ${avroStatus.operationType}")
            }
        }
        return ResponseEntity.ok(status)
    }

    private fun AsynchronousOperationState.toEndpointType(): AsyncOperationState {
        return when (this) {
            AsynchronousOperationState.IN_PROGRESS -> AsyncOperationState.IN_PROGRESS
            AsynchronousOperationState.COMPLETED -> AsyncOperationState.COMPLETED
            AsynchronousOperationState.ABORTED -> AsyncOperationState.ABORTED
            else -> throw CordaRuntimeException("Asynchronous operation state not recognized: ${this.name}")
        }
    }

    private fun ExceptionEnvelope.toEndpointType(): AsyncError {
        return AsyncError(
            errorMessage,
            mapOf("type" to errorType)
        )
    }

    private fun sendAsynchronousRequest(
        requestTime: Instant,
        virtualNodeShortId: String,
        cpiFileChecksum: String,
        actor: String
    ): String {
        val requestId = UUID.randomUUID().toString()
        val request = VirtualNodeCpiUpgradeRequest(
            requestId,
            virtualNodeShortId,
            cpiFileChecksum,
            actor,
        )

        sendAsync(
            virtualNodeShortId,
            VirtualNodeAsynchronousRequest(
                requestTime,
                requestId,
                request
            )
        )

        return requestId
    }

    /**
     * Publishes a virtual node create request onto the message bus that results in persistence of a new virtual node
     *  in the database, as well as a copy of the persisted object being published back into the bus
     *
     * @property VirtualNodeRequest is contains the data we want to use to construct our virtual node
     * @throws IllegalStateException is thrown if the component isn't running and therefore able to service requests.
     * @return [VirtualNodeInfo] which is a data class containing information on the virtual node created
     *
     * @see VirtualNodeInfo
     */
    override fun createVirtualNode(request: VirtualNodeRequest): VirtualNodeInfo {
        val instant = clock.instant()
        if (!isRunning) throw IllegalStateException(
            "${this.javaClass.simpleName} is not running! Its status is: ${lifecycleCoordinator.status}"
        )
        validateX500Name(request.x500Name)

        val actor = CURRENT_RPC_CONTEXT.get().principal
        val rpcRequest = with(request) {
            VirtualNodeManagementRequest(
                instant,
                VirtualNodeCreateRequest(
                    x500Name,
                    cpiFileChecksum,
                    vaultDdlConnection,
                    vaultDmlConnection,
                    cryptoDdlConnection,
                    cryptoDmlConnection,
                    uniquenessDdlConnection,
                    uniquenessDmlConnection,
                    actor
                )
            )
        }
        val resp = sendAndReceive(rpcRequest)
        return when (val resolvedResponse = resp.responseType) {
            is VirtualNodeCreateResponse -> {
                // Convert response into expected type
                resolvedResponse.run {
                    VirtualNodeInfo(
                        HoldingIdentity(MemberX500Name.parse(x500Name), mgmGroupId).toEndpointType(),
                        CpiIdentifier.fromAvro(cpiIdentifier),
                        vaultDdlConnectionId,
                        vaultDmlConnectionId,
                        cryptoDdlConnectionId,
                        cryptoDmlConnectionId,
                        uniquenessDdlConnectionId,
                        uniquenessDmlConnectionId,
                        hsmConnectionId,
                        flowP2pOperationalStatus,
                        flowStartOperationalStatus,
                        flowOperationalStatus,
                        vaultDbOperationalStatus
                    )
                }
            }
            is VirtualNodeManagementResponseFailure -> throw translate(resolvedResponse.exception)
            else -> throw UnknownResponseTypeException(resp.responseType::class.java.name)
        }
    }

    private fun HoldingIdentity.toEndpointType(): HoldingIdentityEndpointType =
        HoldingIdentityEndpointType(x500Name.toString(), groupId, shortHash.value, fullHash)

    private fun net.corda.virtualnode.VirtualNodeInfo.toEndpointType(): VirtualNodeInfo =
        VirtualNodeInfo(
            holdingIdentity.toEndpointType(),
            cpiIdentifier.toEndpointType(),
            vaultDdlConnectionId?.toString(),
            vaultDmlConnectionId.toString(),
            cryptoDdlConnectionId?.toString(),
            cryptoDmlConnectionId.toString(),
            uniquenessDdlConnectionId?.toString(),
            uniquenessDmlConnectionId.toString(),
            hsmConnectionId.toString(),
            flowP2pOperationalStatus,
            flowStartOperationalStatus,
            flowOperationalStatus,
            vaultDbOperationalStatus
        )

    private fun net.corda.libs.packaging.core.CpiIdentifier.toEndpointType(): CpiIdentifier =
        CpiIdentifier(name, version, signerSummaryHash?.toString())

    /** Validates the [x500Name]. */
    private fun validateX500Name(x500Name: String) = try {
        MemberX500Name.parse(x500Name)
    } catch (e: Exception) {
        logger.warn("Configuration Management  X500 name \"$x500Name\" could not be parsed. Cause: ${e.message}")
        val message = "X500 name \"$x500Name\" could not be parsed. Cause: ${e.message}"
        throw InvalidInputDataException(message)
    }

    // Mandatory lifecycle methods - def to coordinator
    override val isRunning get() = lifecycleCoordinator.isRunning
    override fun start() = lifecycleCoordinator.start()
    override fun stop() = lifecycleCoordinator.stop()
}
