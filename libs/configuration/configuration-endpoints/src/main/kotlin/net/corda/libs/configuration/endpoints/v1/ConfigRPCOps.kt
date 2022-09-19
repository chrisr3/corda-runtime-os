package net.corda.libs.configuration.endpoints.v1

import net.corda.httprpc.RpcOps
import net.corda.httprpc.annotations.HttpRpcGET
import net.corda.httprpc.annotations.HttpRpcPUT
import net.corda.httprpc.annotations.HttpRpcPathParameter
import net.corda.httprpc.annotations.HttpRpcRequestBodyParameter
import net.corda.httprpc.annotations.HttpRpcResource
import net.corda.libs.configuration.endpoints.v1.types.GetConfigResponse
import net.corda.libs.configuration.endpoints.v1.types.UpdateConfigParameters
import net.corda.libs.configuration.endpoints.v1.types.UpdateConfigResponse

/** RPC operations for cluster configuration management. */
@HttpRpcResource(
    name = "Configuration API",
    description = "The Configuration API consists of a number of endpoints used to manage the configuration of Corda clusters.",
    path = "config"
)
interface ConfigRPCOps : RpcOps {

    /**
     * Sends a request to update the clusters configuration.
     *
     * @property section Section of the configuration to be updated.
     * @property version Version number used for optimistic locking.
     * @property config Updated configuration in JSON or HOCON format.
     * @property schemaVersion Schema version of the configuration.
     * @throws `ConfigRPCOpsServiceException` If the updated configuration could not be published.
     * @throws `HttpApiException` If the request returns an exceptional response.
     */
    @HttpRpcPUT(
        title = "Update cluster configuration",
        description = "This method updates a section of the cluster configuration.",
        responseDescription = "The updated cluster configuration for the specified section."
    )
    fun updateConfig(
        @HttpRpcRequestBodyParameter(description = "Details of the updated configuration")
        request: UpdateConfigParameters
    ): UpdateConfigResponse

    /**
     * Get the configuration data the cluster is set with for a specific [section].
     * @param section the top level section of cluster configuration to return.
     */
    @HttpRpcGET(
        path = "{section}",
        title = "Get Configuration.",
        description = "This method returns the 'active' configuration for the given section, " +
                "in both the 'raw' format and with defaults applied.",
        responseDescription = "The Configuration for the given section."
    )
    fun get(
        @HttpRpcPathParameter(description = "Section name for the configuration.")
        section: String
    ): GetConfigResponse
}