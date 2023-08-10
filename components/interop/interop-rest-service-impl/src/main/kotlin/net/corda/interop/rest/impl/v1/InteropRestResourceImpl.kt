package net.corda.interop.rest.impl.v1

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.configuration.read.ConfigurationReadService
import net.corda.crypto.core.ShortHash
import net.corda.crypto.core.ShortHashException
import net.corda.interop.core.InteropIdentity
import net.corda.interop.core.Utils
import net.corda.interop.group.policy.read.InteropGroupPolicyReadService
import net.corda.interop.identity.cache.InteropIdentityRegistryService
import net.corda.interop.identity.write.InteropIdentityWriteService
import net.corda.libs.interop.endpoints.v1.InteropRestResource
import net.corda.libs.interop.endpoints.v1.types.CreateInteropIdentityRest
import net.corda.libs.interop.endpoints.v1.types.ExportInteropIdentityRest
import net.corda.libs.interop.endpoints.v1.types.ImportInteropIdentityRest
import net.corda.libs.interop.endpoints.v1.types.InteropIdentityResponse
import net.corda.lifecycle.DependentComponents
import net.corda.lifecycle.Lifecycle
import net.corda.lifecycle.RegistrationStatusChangeEvent
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleCoordinatorName
import net.corda.lifecycle.LifecycleStatus
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.membership.group.policy.validation.InteropGroupPolicyValidator
import net.corda.rest.PluggableRestResource
import net.corda.rest.exception.BadRequestException
import net.corda.rest.exception.InvalidInputDataException
import net.corda.rest.response.ResponseEntity
import net.corda.schema.configuration.ConfigKeys
import net.corda.utilities.debug
import net.corda.v5.application.interop.facade.FacadeId
import net.corda.v5.base.types.MemberX500Name
import net.corda.virtualnode.VirtualNodeInfo
import net.corda.virtualnode.read.VirtualNodeInfoReadService
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

@Suppress("LongParameterList")
@Component(service = [PluggableRestResource::class])
internal class InteropRestResourceImpl @Activate constructor(
    @Reference(service = LifecycleCoordinatorFactory::class)
    coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = ConfigurationReadService::class)
    private val configurationReadService: ConfigurationReadService,
    @Reference(service = InteropIdentityRegistryService::class)
    private val interopIdentityRegistryService: InteropIdentityRegistryService,
    @Reference(service = InteropIdentityWriteService::class)
    private val interopIdentityWriteService: InteropIdentityWriteService,
    @Reference(service = VirtualNodeInfoReadService::class)
    private val virtualNodeInfoReadService: VirtualNodeInfoReadService,
    @Reference(service = InteropGroupPolicyReadService::class)
    private val interopGroupPolicyReadService: InteropGroupPolicyReadService,
    @Reference(service = InteropGroupPolicyValidator::class)
    private val interopGroupPolicyValidator: InteropGroupPolicyValidator
) : InteropRestResource, PluggableRestResource<InteropRestResource>, Lifecycle {

    private companion object {
        private val requiredKeys = setOf(ConfigKeys.MESSAGING_CONFIG, ConfigKeys.REST_CONFIG)
        val logger: Logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
        private const val CONFIG_HANDLE = "CONFIG_HANDLE"
    }

    /**
     * These are identical at the moment, but this may not be the case in future revisions of corda
     * Separate methods have been created so that it is obvious to future maintainers which is required
     */
    private fun VirtualNodeInfo.getVNodeShortHash() = this.holdingIdentity.shortHash.toString()
    private fun VirtualNodeInfo.getHoldingIdentityShortHash() = this.holdingIdentity.shortHash.toString()

    // RestResource values
    override val targetInterface: Class<InteropRestResource> = InteropRestResource::class.java
    override val protocolVersion = 1

    //TODO Add correct group policy as part of the import task CORE-10450
    override fun getInterOpGroups(holdingIdentityShortHash: String): Map<UUID, String> {
        val vNodeInfo = getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash)
        val cacheView =
            interopIdentityRegistryService.getVirtualNodeRegistryView(vNodeInfo.getVNodeShortHash())
        return cacheView.getOwnedIdentities().keys.associate {
            Pair(UUID.fromString(it), interopGroupPolicyReadService.getGroupPolicy(it) ?: "")
        }
    }

    private fun getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash: String): VirtualNodeInfo {
        return virtualNodeInfoReadService.getByHoldingIdentityShortHash(ShortHash.of(holdingIdentityShortHash))
            ?: throw InvalidInputDataException(
                "No virtual node found with short hash $holdingIdentityShortHash."
            )
    }

    private fun requireValidUUID(uuidString: String, lazyMessage: () -> String): UUID {
        return try {
            UUID.fromString(uuidString)
        } catch (e: Exception) {
            throw IllegalArgumentException(lazyMessage())
        }
    }

    private fun getGroupIdFieldFromGroupPolicy(groupPolicyString: String): String {
        val groupPolicyJson = ObjectMapper().readTree(groupPolicyString)

        check(groupPolicyJson.has("groupId")) {
            "Malformed group policy json. Group ID field missing from policy."
        }

        check(groupPolicyJson["groupId"].isTextual) {
            "Malformed group policy json. Group ID field is present but is not a text node."
        }

        return groupPolicyJson["groupId"].asText()
    }

    @Suppress("ForbiddenComment")
    override fun createInterOpIdentity(
        createInteropIdentityRestRequest: CreateInteropIdentityRest.Request,
        holdingIdentityShortHash: String
    ): CreateInteropIdentityRest.Response {
        try {
            ShortHash.parse(holdingIdentityShortHash)
        } catch (e: ShortHashException) {
            throw BadRequestException("Invalid holding identity short hash${e.message?.let { ": $it" }}")
        }

        val vNodeInfo = getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash)

        val ownedInteropIdentityX500 = MemberX500Name(
            createInteropIdentityRestRequest.applicationName,
            vNodeInfo.holdingIdentity.x500Name.locality,
            vNodeInfo.holdingIdentity.x500Name.country
        ).toString()

        interopGroupPolicyValidator.validateGroupPolicy(createInteropIdentityRestRequest.groupPolicy)

        try {
            MemberX500Name.parse(ownedInteropIdentityX500)
        } catch (e: Exception) {
            throw InvalidInputDataException(
                "X500 name \"$ownedInteropIdentityX500\" could not be parsed. Cause: ${e.message}"
            )
        }

        val groupIdField = try {
            getGroupIdFieldFromGroupPolicy(createInteropIdentityRestRequest.groupPolicy)
        } catch (e: Exception) {
            throw InvalidInputDataException(e.message!!)
        }

        if (groupIdField == "CREATE_ID") {
            if (!createInteropIdentityRestRequest.members.isNullOrEmpty()) {
                throw InvalidInputDataException(
                    "Cannot import members when creating a new interop group."
                )
            }
        } else {
            requireValidUUID(groupIdField) {
                "Malformed group policy. Group ID must be a valid uuid or 'CREATE_ID', got: $groupIdField"
            }
        }

        val interopGroupId = interopIdentityWriteService.publishGroupPolicy(
            groupIdField,
            createInteropIdentityRestRequest.groupPolicy
        )

        // Create the owned interop identity
        interopIdentityWriteService.addInteropIdentity(
            vNodeInfo.getVNodeShortHash(),
            InteropIdentity(
                groupId = interopGroupId,
                x500Name = ownedInteropIdentityX500,
                owningVirtualNodeShortHash = vNodeInfo.getVNodeShortHash(),
                facadeIds = facadeIds(),
                applicationName = createInteropIdentityRestRequest.applicationName,
                // TODO: Fetch these from the member info topic
                endpointUrl = "endpointUrl",
                endpointProtocol = "endpointProtocol"
            )
        )

        // If any exported members are present, import them
        createInteropIdentityRestRequest.members?.forEach { member ->
            interopIdentityWriteService.addInteropIdentity(
                vNodeInfo.getVNodeShortHash(),
                InteropIdentity(
                    groupId = interopGroupId,
                    x500Name = member.x500Name,
                    facadeIds = member.facadeIds.map { FacadeId.of(it) },
                    applicationName = MemberX500Name.parse(member.x500Name).organization,
                    endpointUrl = member.endpointUrl,
                    endpointProtocol = member.endpointProtocol,
                )
            )
        }

        logger.info("InteropIdentity created.")

        return CreateInteropIdentityRest.Response(
            Utils.computeShortHash(ownedInteropIdentityX500, interopGroupId)
        )
    }

    private fun facadeIds() = listOf(
        FacadeId.of("org.corda.interop/platform/tokens/v1.0"),
        FacadeId.of("org.corda.interop/platform/tokens/v2.0"),
        FacadeId.of("org.corda.interop/platform/tokens/v3.0")
    )

    override fun getInterOpIdentities(holdingIdentityShortHash: String): List<InteropIdentityResponse> {
        val vNodeInfo = getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash)
        val vNodeShortHash = vNodeInfo.getVNodeShortHash()
        val cacheView = interopIdentityRegistryService.getVirtualNodeRegistryView(vNodeShortHash)
        val interopIdentities = cacheView.getIdentities()
        return interopIdentities.map { interopIdentity ->
            InteropIdentityResponse(
                interopIdentity.x500Name,
                UUID.fromString(interopIdentity.groupId),
                vNodeShortHash,
                interopIdentity.facadeIds,
                MemberX500Name.parse(interopIdentity.x500Name).organization,
                interopIdentity.endpointUrl,
                interopIdentity.endpointProtocol
            )
        }.toList()
    }

    override fun exportInterOpIdentity(
        holdingIdentityShortHash: String,
        interopIdentityShortHash: String
    ): ExportInteropIdentityRest.Response {
        val vNodeInfo = getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash)
        val vNodeShortHash = vNodeInfo.getVNodeShortHash()
        val registryView = interopIdentityRegistryService.getVirtualNodeRegistryView(vNodeShortHash)
        val interopIdentityMap = registryView.getIdentitiesByShortHash()
        val interopIdentityToExport = if (interopIdentityMap.containsKey(interopIdentityShortHash)) {
            interopIdentityMap[interopIdentityShortHash]!!
        } else {
            throw InvalidInputDataException(
                "No interop identity with short hash '$interopIdentityShortHash' found for holding identity '$holdingIdentityShortHash'."
            )
        }
        if (interopIdentityToExport.owningVirtualNodeShortHash != vNodeShortHash) {
            throw InvalidInputDataException(
                "Only owned identities may be exported. Y" +
                        "THe Requested Identity shorthash is: ${interopIdentityToExport.owningVirtualNodeShortHash}" +
                        "& yours is $vNodeShortHash"
            )
        }
        val groupPolicy = checkNotNull(interopGroupPolicyReadService.getGroupPolicy(interopIdentityToExport.groupId)) {
            "Could not find group policy info for interop identity $interopIdentityShortHash"
        }
        return ExportInteropIdentityRest.Response(
            listOf(
                ExportInteropIdentityRest.MemberData(
                    interopIdentityToExport.x500Name,
                    interopIdentityToExport.owningVirtualNodeShortHash!!,
                    interopIdentityToExport.endpointUrl,
                    interopIdentityToExport.endpointProtocol,
                    interopIdentityToExport.facadeIds.map { it.toString() }
                )
            ),
            groupPolicy
        )
    }

    override fun importInterOpIdentity(
        importInteropIdentityRestRequest: ImportInteropIdentityRest.Request,
        holdingIdentityShortHash: String
    ): ResponseEntity<String> {
        if (importInteropIdentityRestRequest.members.isEmpty()) {
            throw InvalidInputDataException(
                "No members provided in request, nothing to import."
            )
        }

        val vNodeInfo = getAndValidateVirtualNodeInfoByShortHash(holdingIdentityShortHash)
        val vNodeShortHash = vNodeInfo.getVNodeShortHash()

        val interopGroupId = try {
            val groupIdField = getGroupIdFieldFromGroupPolicy(importInteropIdentityRestRequest.groupPolicy)
            requireValidUUID(groupIdField) {
                "Malformed group policy, groupId is not a valid UUID string."
            }
            groupIdField
        } catch (e: Exception) {
            throw InvalidInputDataException(e.message!!)
        }

        interopIdentityWriteService.publishGroupPolicy(
            interopGroupId,
            importInteropIdentityRestRequest.groupPolicy
        )

        importInteropIdentityRestRequest.members.forEach { member ->
            interopIdentityWriteService.addInteropIdentity(
                vNodeShortHash,
                InteropIdentity(
                    groupId = interopGroupId,
                    x500Name = member.x500Name,
                    facadeIds = member.facadeIds.map { FacadeId.of(it) },
                    applicationName = MemberX500Name.parse(member.x500Name).organization,
                    endpointUrl = member.endpointUrl,
                    endpointProtocol = member.endpointProtocol
                )
            )
        }

        logger.info("Interop identity imported.")

        return ResponseEntity.ok("OK")
    }

    // Lifecycle
    private val dependentComponents = DependentComponents.of(
        ::configurationReadService,
        ::interopIdentityRegistryService,
        ::interopIdentityWriteService,
        ::interopGroupPolicyReadService,
        ::interopGroupPolicyValidator
    )

    private val lifecycleCoordinator = coordinatorFactory.createCoordinator(
        LifecycleCoordinatorName.forComponent<InteropRestResource>()
    ) { event: LifecycleEvent, coordinator: LifecycleCoordinator ->
        when (event) {
            is StartEvent -> {
                dependentComponents.registerAndStartAll(coordinator)
                coordinator.updateStatus(LifecycleStatus.UP)
            }

            is StopEvent -> coordinator.updateStatus(LifecycleStatus.DOWN)
            is RegistrationStatusChangeEvent -> {
                when (event.status) {
                    LifecycleStatus.ERROR -> {
                        coordinator.closeManagedResources(setOf(CONFIG_HANDLE))
                        coordinator.postEvent(StopEvent(errored = true))
                    }

                    LifecycleStatus.UP -> {
                        // Receive updates to the REST and Messaging config
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
            }
        }
    }

    // Mandatory lifecycle methods - def to coordinator
    override val isRunning get() = lifecycleCoordinator.isRunning
    override fun start() = lifecycleCoordinator.start()
    override fun stop() = lifecycleCoordinator.stop()

}