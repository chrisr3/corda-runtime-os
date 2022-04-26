package net.corda.crypto.service

import net.corda.v5.cipher.suite.CryptoService
import net.corda.v5.cipher.suite.schemes.KeyScheme

/**
 * Defines a reference to an instance of [CryptoService] with configuration information per tenant.
 */
@Suppress("LongParameterList")
class CryptoServiceRef(
    val tenantId: String,
    val category: String,
    val keyScheme: KeyScheme,
    val masterKeyAlias: String?,
    val aliasSecret: ByteArray?,
    val instance: CryptoService
)