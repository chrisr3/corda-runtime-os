package net.corda.ledger.utxo.token.cache.entities

import java.math.BigDecimal

data class ClaimQuery(
    val externalEventRequestId: String,
    val flowId: String,
    val targetAmount: BigDecimal,
    override val tagRegex: String?,
    override val ownerHash: String?,
    override val poolKey: TokenPoolKey
): TokenFilter
