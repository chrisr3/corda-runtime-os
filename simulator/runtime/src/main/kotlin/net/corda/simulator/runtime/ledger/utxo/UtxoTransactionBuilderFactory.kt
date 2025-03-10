package net.corda.simulator.runtime.ledger.utxo

import net.corda.simulator.SimulatorConfiguration
import net.corda.v5.application.crypto.SigningService
import net.corda.v5.application.persistence.PersistenceService
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.utxo.transaction.UtxoTransactionBuilder

/**
 * A factory to build [UtxoTransactionBuilder]
 */
fun interface UtxoTransactionBuilderFactory {
    fun createUtxoTransactionBuilder(
        signingService: SigningService,
        persistenceService: PersistenceService,
        configuration: SimulatorConfiguration,
        notaryLookup: NotaryLookup
    ): UtxoTransactionBuilder
}

fun utxoTransactionBuilderFactoryBase(): UtxoTransactionBuilderFactory =
    UtxoTransactionBuilderFactory { ss, per, c, nl ->
        UtxoTransactionBuilderBase(
            signingService = ss,
            persistenceService = per,
            configuration = c,
            notaryLookup = nl
        )
    }