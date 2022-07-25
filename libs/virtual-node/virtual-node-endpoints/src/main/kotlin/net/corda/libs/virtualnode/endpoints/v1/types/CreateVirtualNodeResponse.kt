package net.corda.libs.virtualnode.endpoints.v1.types

import net.corda.libs.cpiupload.endpoints.v1.CpiIdentifier

/**
 * The data object received via HTTP in response to a request to create a virtual node.
 *
 * Exactly one of [cpiIdentifier] and [cpiFileChecksum] should be null.
 *
 * @param x500Name The X500 name for the new virtual node.
 * @param cpiIdentifier The long identifier of the CPI the virtual node is being created for.
 * @param cpiFileChecksum The checksum of the CPI file.
 * @param mgmGroupId The identifier of the CPI's MGM.
 * @param holdingIdentityShortHash The holding identifier short hash for the virtual node.
 * @param vaultDdlConnectionId The ID of the connection for DDL operations in virtual node's vault database.
 * @param vaultDmlConnectionId The ID of the connection for DML operations in virtual node's vault database.
 * @param cryptoDdlConnectionId The ID of the connection for DDL operations in virtual node's crypto database.
 * @param cryptoDmlConnectionId The ID of the connection for DML operations in virtual node's crypto database.
 * @param virtualNodeState The state of the virtual node.
 */
data class CreateVirtualNodeResponse(
    val x500Name: String,
    val cpiIdentifier: CpiIdentifier,
    val cpiFileChecksum: String?,
    val mgmGroupId: String,
    val holdingIdentityShortHash: String,
    val vaultDdlConnectionId: String? = null,
    val vaultDmlConnectionId: String,
    val cryptoDdlConnectionId: String? = null,
    val cryptoDmlConnectionId: String,
    val virtualNodeState: String
)
