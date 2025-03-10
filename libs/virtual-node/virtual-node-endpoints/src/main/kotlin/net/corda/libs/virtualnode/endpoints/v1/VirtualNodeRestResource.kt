package net.corda.libs.virtualnode.endpoints.v1

import net.corda.libs.virtualnode.endpoints.v1.types.ChangeVirtualNodeStateResponse
import net.corda.libs.virtualnode.endpoints.v1.types.CreateVirtualNodeRequest
import net.corda.libs.virtualnode.endpoints.v1.types.HoldingIdentity
import net.corda.libs.virtualnode.endpoints.v1.types.VirtualNodeInfo
import net.corda.libs.virtualnode.endpoints.v1.types.VirtualNodes
import net.corda.rest.RestResource
import net.corda.rest.annotations.ClientRequestBodyParameter
import net.corda.rest.annotations.HttpGET
import net.corda.rest.annotations.HttpPOST
import net.corda.rest.annotations.HttpPUT
import net.corda.rest.annotations.HttpRestResource
import net.corda.rest.annotations.RestPathParameter
import net.corda.rest.asynchronous.v1.AsyncOperationStatus
import net.corda.rest.asynchronous.v1.AsyncResponse
import net.corda.rest.response.ResponseEntity

/** Rest operations for virtual node management. */
@HttpRestResource(
    name = "Virtual Node API",
    description = "The Virtual Nodes API consists of a number of endpoints to manage virtual nodes.",
    path = "virtualnode"
)
interface VirtualNodeRestResource : RestResource {

    /**
     * Requests the creation of a virtual node.
     *
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpPOST(
        title = "Create virtual node",
        description = "This method creates a new virtual node.",
        responseDescription = "The details of the created virtual node."
    )
    fun createVirtualNode(
        @ClientRequestBodyParameter(description = "Details of the virtual node to be created")
        request: CreateVirtualNodeRequest
    ): ResponseEntity<AsyncResponse>

    /**
     * Lists all virtual nodes onboarded to the cluster.
     *
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpGET(
        title = "Lists all virtual nodes",
        description = "This method lists all virtual nodes in the cluster.",
        responseDescription = "List of virtual node details."
    )
    fun getAllVirtualNodes(): VirtualNodes

    /**
     * Updates a virtual nodes state.
     *
     * @throws `VirtualNodeRPCOpsServiceException` If the virtual node update request could not be published.
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpPUT(
        path = "{virtualNodeShortId}/state/{newState}",
        title = "Update virtual node state",
        description = "This method updates the state of a new virtual node to one of the pre-defined values.",
        responseDescription = "Complete information about updated virtual node which will also contain the updated state."
    )
    fun updateVirtualNodeState(
        @RestPathParameter(description = "Short ID of the virtual node instance to update")
        virtualNodeShortId: String,
        @RestPathParameter(description = "State to transition virtual node instance into. " +
                "Possible values are: MAINTENANCE and ACTIVE.")
        newState: String
    ): ChangeVirtualNodeStateResponse

    /**
     * Returns the VirtualNodeInfo for a given [HoldingIdentity].
     *
     * @throws 'ResourceNotFoundException' If the virtual node was not found.
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpGET(
        path = "{holdingIdentityShortHash}",
        title = "Gets the VirtualNodeInfo for a HoldingIdentityShortHash",
        description = "This method returns the VirtualNodeInfo for a given Holding Identity ShortHash.",
        responseDescription = "VirtualNodeInfo for the specified virtual node."
    )
    fun getVirtualNode(
        @RestPathParameter(description = "The short hash of the holding identity; obtained during node registration")
        holdingIdentityShortHash: String
    ): VirtualNodeInfo

    /**
     * Returns the VirtualNodeOperationStatus for a given [requestId].
     */
    @HttpGET(
        path = "status/{requestId}",
        title = "Gets the VirtualNodeOperationStatus for an operation request id.",
        description = "This method returns the VirtualNodeOperationStatus for a given operation request id.",
        responseDescription = "VirtualNodeOperationStatus for the specified virtual node."
    )
    fun getVirtualNodeOperationStatus(
        @RestPathParameter(description = "The requestId for the operation; obtained during node creation/upgrade")
        requestId: String
    ): AsyncOperationStatus


    /**
     * Asynchronous endpoint to upgrade a virtual node's CPI.
     */
    @HttpPUT(
        path = "{virtualNodeShortId}/cpi/{targetCpiFileChecksum}",
        title = "Upgrade a virtual node's CPI.",
        description = "This method upgrades a virtual node's CPI.",
        responseDescription = "Identifier for the request."
    )
    fun upgradeVirtualNode(
        @RestPathParameter(description = "Short ID of the virtual node instance to update")
        virtualNodeShortId: String,
        @RestPathParameter(description = "The file checksum of the CPI to upgrade to.")
        targetCpiFileChecksum: String
    ): ResponseEntity<AsyncResponse>
}
