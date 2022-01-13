package net.corda.libs.permissions.endpoints.v1.user

import net.corda.httprpc.RpcOps
import net.corda.httprpc.annotations.HttpRpcGET
import net.corda.httprpc.annotations.HttpRpcPOST
import net.corda.httprpc.annotations.HttpRpcQueryParameter
import net.corda.httprpc.annotations.HttpRpcRequestBodyParameter
import net.corda.httprpc.annotations.HttpRpcResource
import net.corda.libs.permissions.endpoints.v1.user.types.CreateUserType
import net.corda.libs.permissions.endpoints.v1.user.types.UserResponseType

/**
 * User endpoint exposes HTTP endpoints for management of Users in the RBAC permission system.
 */
@HttpRpcResource(
    name = "UserEndpoint",
    description = "User Management APIs",
    path = "user"
)
interface UserEndpoint : RpcOps {

    /**
     * Create a user in the RBAC permission system.
     */
    @HttpRpcPOST(description = "Create a User", path = "createUser")
    fun createUser(
        @HttpRpcRequestBodyParameter(description = "Details of the user to be created", required = true)
        createUserType: CreateUserType
    ): UserResponseType

    /**
     * Get a user by loginName in the RBAC permission system.
     */
    @HttpRpcGET(description = "Get a User by Login Name", path = "")
    fun getUser(
        @HttpRpcQueryParameter(name = "loginName", description = "Login Name of the user to be returned.", required = true)
        loginName: String
    ): UserResponseType

    /**
     * Assign a Role to a User in the RBAC permission system.
     */
    @HttpRpcPOST(description = "Assign a Role to a User", path = "addRole")
    fun addRole(
        @HttpRpcRequestBodyParameter(description = "User login name to be changed", required = true)
        loginName: String,
        @HttpRpcRequestBodyParameter(description = "Id of the role to associate with this user", required = true)
        roleId: String
    ): UserResponseType

    /**
     * Un-assign a Role from a User in the RBAC permission system.
     */
    @HttpRpcPOST(description = "Un-assign a role from a user", path = "removeRole")
    fun removeRole(
        @HttpRpcRequestBodyParameter(description = "User login name to be changed", required = true)
        loginName: String,
        @HttpRpcRequestBodyParameter(description = "Id of the role to un-assign from this user", required = true)
        roleId: String
    ): UserResponseType
}