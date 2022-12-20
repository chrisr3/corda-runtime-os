package net.corda.ledger.persistence.utxo.impl

import net.corda.ledger.common.data.transaction.SignedTransactionContainer
import net.corda.ledger.common.data.transaction.TransactionStatus
import net.corda.ledger.persistence.utxo.UtxoPersistenceService
import net.corda.ledger.persistence.utxo.UtxoRepository
import net.corda.ledger.persistence.utxo.UtxoTransactionReader
import net.corda.ledger.utxo.data.state.StateAndRefImpl
import net.corda.ledger.utxo.data.state.TransactionStateImpl
import net.corda.ledger.utxo.data.state.getEncumbranceGroup
import net.corda.ledger.utxo.data.transaction.UtxoComponentGroup
import net.corda.ledger.utxo.data.transaction.UtxoOutputInfoComponent
import net.corda.orm.utils.transaction
import net.corda.utilities.time.Clock
import net.corda.v5.application.crypto.DigestService
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.application.serialization.deserialize
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.common.transaction.CordaPackageSummary
import net.corda.v5.ledger.utxo.ContractState
import net.corda.v5.ledger.utxo.StateAndRef
import net.corda.v5.ledger.utxo.StateRef
import javax.persistence.EntityManager

class UtxoPersistenceServiceImpl constructor(
    private val entityManager: EntityManager,
    private val repository: UtxoRepository,
    private val serializationService: SerializationService,
    private val sandboxDigestService: DigestService,
    private val utcClock: Clock
) : UtxoPersistenceService {

    override fun findTransaction(id: String, transactionStatus: TransactionStatus): SignedTransactionContainer? {
        return entityManager.transaction { em ->
            val status = repository.findTransactionStatus(em, id)
            if (status == transactionStatus.value) {
                repository.findTransaction(em, id)
            } else {
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T: ContractState> findUnconsumedRelevantStatesByType(
        stateClass: Class<out T>,
        jPath: String?
    ): List<StateAndRef<T>> {
        val outputsInfoIdx = UtxoComponentGroup.OUTPUTS_INFO.ordinal
        val outputsIdx = UtxoComponentGroup.OUTPUTS.ordinal
        val componentGroups = entityManager.transaction { em ->
            repository.findUnconsumedRelevantStatesByType(em, listOf(outputsInfoIdx, outputsIdx), jPath)
        }.groupBy { it.groupIndex }
        val outputInfos = componentGroups[outputsInfoIdx]
            ?.associate { Pair(it.leafIndex, serializationService.deserialize<UtxoOutputInfoComponent>(it.data)) }
            ?: emptyMap()
        return componentGroups[outputsIdx]?.mapNotNull {
            val info = outputInfos[it.leafIndex]
            requireNotNull(info) {
                "Missing output info at index [${it.leafIndex}] for UTXO transaction with ID [${it.transactionId}]"
            }
            val contractState = serializationService.deserialize<ContractState>(it.data)
            if (stateClass.isInstance(contractState)) {
                StateAndRefImpl(
                    state = TransactionStateImpl(contractState as T, info.notary, info.getEncumbranceGroup()),
                    ref = StateRef(SecureHash.parse(it.transactionId), it.leafIndex)
                )
            } else {
                null
            }
        } ?: emptyList()
    }

    override fun persistTransaction(transaction: UtxoTransactionReader) {
        val nowUtc = utcClock.instant()
        val transactionIdString = transaction.id.toString()

        entityManager.transaction { em ->
            // Insert the Transaction
            repository.persistTransaction(
                em,
                transactionIdString,
                transaction.privacySalt.bytes,
                transaction.account,
                nowUtc
            )

            // Insert the Transactions components
            transaction.rawGroupLists.mapIndexed { groupIndex, leaves ->
                leaves.mapIndexed { leafIndex, data ->
                    repository.persistTransactionComponentLeaf(
                        em,
                        transactionIdString,
                        groupIndex,
                        leafIndex,
                        data,
                        sandboxDigestService.hash(data, DigestAlgorithmName.SHA2_256).toString(),
                        nowUtc
                    )
                }
            }

            // Insert inputs data
            val inputs = transaction.getConsumedStateRefs()
            inputs.forEachIndexed  { index, input ->
                repository.persistTransactionSource(
                    em,
                    transactionIdString,
                    UtxoComponentGroup.INPUTS.ordinal,
                    index,
                    input.transactionHash.toString(),
                    input.index,
                    false,
                    nowUtc
                )
            }

            // Insert outputs data
            transaction.getProducedStates().forEachIndexed { index, stateAndRef ->
                repository.persistTransactionOutput(
                    em,
                    transactionIdString,
                    UtxoComponentGroup.OUTPUTS.ordinal,
                    index,
                    stateAndRef.state.contractState::class.java.canonicalName,
                    timestamp = nowUtc
                )
            }

            // Insert relevancy information for outputs
            transaction.relevantStatesIndexes.forEach { relevantStateIndex ->
                repository.persistTransactionRelevantStates(
                    em,
                    transactionIdString,
                    UtxoComponentGroup.OUTPUTS.ordinal,
                    relevantStateIndex,
                    consumed = false,
                    nowUtc
                )
            }

            // Mark inputs as consumed in relevancy table
            if (inputs.isNotEmpty()) {
                repository.markTransactionRelevantStatesConsumed(
                    em,
                    inputs,
                    UtxoComponentGroup.OUTPUTS.ordinal
                )
            }

            // Insert the Transactions signatures
            transaction.signatures.forEachIndexed { index, digitalSignatureAndMetadata ->
                repository.persistTransactionSignature(
                    em,
                    transactionIdString,
                    index,
                    digitalSignatureAndMetadata,
                    nowUtc
                )
            }

            // Insert the transactions current status
            repository.persistTransactionStatus(
                em,
                transactionIdString,
                transaction.status,
                nowUtc
            )

            // Insert the CPK details liked to this transaction
            // TODOs: The CPK file meta does not exist yet, this will be implemented by
            // https://r3-cev.atlassian.net/browse/CORE-7626
        }
    }

    override fun persistTransactionIfDoesNotExist(
        transaction: SignedTransactionContainer,
        transactionStatus: TransactionStatus,
        account: String
    ): Pair<String?, List<CordaPackageSummary>> {
        val nowUtc = utcClock.instant()

        return entityManager.transaction { em ->
            val transactionIdString = transaction.id.toString()

            val status = repository.findTransactionStatus(em, transactionIdString)

            if (status != null) {
                return@transaction status to emptyList()
            }

            // Insert the Transaction
            repository.persistTransaction(
                em,
                transactionIdString,
                transaction.wireTransaction.privacySalt.bytes,
                account,
                nowUtc
            )

            // Insert the Transactions components
            transaction.wireTransaction.componentGroupLists.mapIndexed { groupIndex, leaves ->
                leaves.mapIndexed { leafIndex, data ->
                    repository.persistTransactionComponentLeaf(
                        em,
                        transactionIdString,
                        groupIndex,
                        leafIndex,
                        data,
                        sandboxDigestService.hash(data, DigestAlgorithmName.SHA2_256).toString(),
                        nowUtc
                    )
                }
            }

            // Insert the Transactions signatures
            transaction.signatures.forEachIndexed { index, digitalSignatureAndMetadata ->
                repository.persistTransactionSignature(
                    em,
                    transactionIdString,
                    index,
                    digitalSignatureAndMetadata,
                    nowUtc
                )
            }

            // Insert the transactions current status
            repository.persistTransactionStatus(
                em,
                transactionIdString,
                transactionStatus,
                nowUtc
            )

            // Insert the CPK details liked to this transaction
            // TODOs: The CPK file meta does not exist yet, this will be implemented by
            // https://r3-cev.atlassian.net/browse/CORE-7626

            return null to emptyList()
        }
    }

    override fun updateStatus(id: String, transactionStatus: TransactionStatus) {
        entityManager.transaction { em ->
            repository.persistTransactionStatus(em, id, transactionStatus, utcClock.instant())
        }
    }
}
