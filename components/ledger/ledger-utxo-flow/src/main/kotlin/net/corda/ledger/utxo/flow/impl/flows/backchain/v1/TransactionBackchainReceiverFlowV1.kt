package net.corda.ledger.utxo.flow.impl.flows.backchain.v1

import net.corda.flow.application.services.FlowConfigService
import net.corda.crypto.core.parseSecureHash
import net.corda.ledger.common.data.transaction.TransactionMetadataInternal
import net.corda.ledger.common.data.transaction.TransactionStatus.UNVERIFIED
import net.corda.ledger.utxo.flow.impl.UtxoLedgerMetricRecorder
import net.corda.ledger.utxo.flow.impl.flows.backchain.TopologicalSort
import net.corda.ledger.utxo.flow.impl.flows.backchain.TransactionBackChainResolutionVersion
import net.corda.ledger.utxo.flow.impl.flows.backchain.dependencies
import net.corda.ledger.utxo.flow.impl.groupparameters.verifier.SignedGroupParametersVerifier
import net.corda.ledger.utxo.flow.impl.persistence.TransactionExistenceStatus
import net.corda.ledger.utxo.flow.impl.persistence.UtxoLedgerGroupParametersPersistenceService
import net.corda.ledger.utxo.flow.impl.persistence.UtxoLedgerPersistenceService
import net.corda.membership.lib.SignedGroupParameters
import net.corda.sandbox.CordaSystemFlow
import net.corda.schema.configuration.ConfigKeys.UTXO_LEDGER_CONFIG
import net.corda.utilities.trace
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.SubFlow
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.exceptions.CordaRuntimeException
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import org.slf4j.LoggerFactory

/**
 * The V2 protocol is an extension of the V1 protocol, which can be enabled via a switch (on both sides).
 * In order to avoid huge code duplication, we kept V1 class implementing both protocols and added a switch that makes
 * it behave according to the V2 protocol.
 */

@CordaSystemFlow
class TransactionBackchainReceiverFlowV1(
    private val initialTransactionIds: Set<SecureHash>,
    private val originalTransactionsToRetrieve: Set<SecureHash>,
    private val session: FlowSession,
    val version: TransactionBackChainResolutionVersion
) : SubFlow<TopologicalSort> {

    private companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
        private const val BACKCHAIN_BATCH_CONFIG_PATH = "backchain.batchSize"
    }

    @CordaInject
    lateinit var utxoLedgerPersistenceService: UtxoLedgerPersistenceService

    @CordaInject
    lateinit var utxoLedgerMetricRecorder: UtxoLedgerMetricRecorder

    @CordaInject
    lateinit var utxoLedgerGroupParametersPersistenceService: UtxoLedgerGroupParametersPersistenceService

    @CordaInject
    lateinit var signedGroupParametersVerifier: SignedGroupParametersVerifier

    @CordaInject
    lateinit var flowConfigService: FlowConfigService

    @Suspendable
    override fun call(): TopologicalSort {
        // Using a [Set] here ensures that if two or more transactions at the same level in the graph are dependent on the same transaction
        // then when the second and subsequent transactions see this dependency to add it to [transactionsToRetrieve] it will only exist
        // once and be retrieved once due to the properties of a [Set].
        val transactionsToRetrieve = LinkedHashSet(originalTransactionsToRetrieve)

        val sortedTransactionIds = TopologicalSort()

        val ledgerConfig = flowConfigService.getConfig(UTXO_LEDGER_CONFIG)

        val batchSize = ledgerConfig.getInt(BACKCHAIN_BATCH_CONFIG_PATH)

        while (transactionsToRetrieve.isNotEmpty()) {
            val batch = transactionsToRetrieve.take(batchSize)

            log.trace {
                "Backchain resolution of $initialTransactionIds - Requesting the content of transactions $batch from transaction backchain"
            }

            @Suppress("unchecked_cast")
            val retrievedTransactions = session.sendAndReceive(
                List::class.java,
                TransactionBackchainRequestV1.Get(batch.toSet())
            ) as List<UtxoSignedTransaction>

            log.trace { "Backchain resolution of $initialTransactionIds - Received content for transactions $batch" }

            for (retrievedTransaction in retrievedTransactions) {

                val retrievedTransactionId = retrievedTransaction.id

                require(retrievedTransactionId in batch) {
                    "Backchain resolution of $initialTransactionIds - Received transaction $retrievedTransactionId which was not " +
                            "requested in the last batch $batch"
                }

                retrieveGroupParameters(retrievedTransaction)
                val (status, _) = utxoLedgerPersistenceService.persistIfDoesNotExist(retrievedTransaction, UNVERIFIED)

                transactionsToRetrieve.remove(retrievedTransactionId)

                when (status) {
                    TransactionExistenceStatus.DOES_NOT_EXIST -> log.trace {
                        "Backchain resolution of $initialTransactionIds - Persisted transaction $retrievedTransactionId as " +
                                "unverified"
                    }
                    TransactionExistenceStatus.UNVERIFIED -> log.trace {
                        "Backchain resolution of $initialTransactionIds - Transaction $retrievedTransactionId already exists as " +
                                "unverified"
                    }
                    TransactionExistenceStatus.VERIFIED -> log.trace {
                        "Backchain resolution of $initialTransactionIds - Transaction $retrievedTransactionId already exists as " +
                                "verified"
                    }
                }

                if (status != TransactionExistenceStatus.VERIFIED) {
                    addUnseenDependenciesToRetrieve(retrievedTransaction, sortedTransactionIds, transactionsToRetrieve)
                }
            }
        }

        session.send(TransactionBackchainRequestV1.Stop)

        utxoLedgerMetricRecorder.recordTransactionBackchainLength(sortedTransactionIds.size)

        return sortedTransactionIds
    }

    @Suspendable
    private fun retrieveGroupParameters(
        retrievedTransaction: UtxoSignedTransaction
    ) {
        if (version == TransactionBackChainResolutionVersion.V1) {
            log.trace { "Backchain resolution of $initialTransactionIds - Group parameters retrieval is not available in V1" }
            return
        }
        val retrievedTransactionId = retrievedTransaction.id
        val groupParametersHash = parseSecureHash(requireNotNull(
            (retrievedTransaction.metadata as TransactionMetadataInternal).getMembershipGroupParametersHash()
        ) {
            "Received transaction does not have group parameters in its metadata."
        })

        if (utxoLedgerGroupParametersPersistenceService.find(groupParametersHash) != null) {
            return
        }
        log.trace {
            "Backchain resolution of $initialTransactionIds - Retrieving group parameters ($groupParametersHash) " +
                    "for transaction ($retrievedTransactionId)"
        }
        val retrievedSignedGroupParameters = session.sendAndReceive(
            SignedGroupParameters::class.java,
            TransactionBackchainRequestV1.GetSignedGroupParameters(groupParametersHash)
        )
        if (retrievedSignedGroupParameters.hash != groupParametersHash) {
            val message =
                "Backchain resolution of $initialTransactionIds - Requested group parameters: $groupParametersHash, " +
                        "but received: ${retrievedSignedGroupParameters.hash}"
            log.warn(message)
            throw CordaRuntimeException(message)
        }
        signedGroupParametersVerifier.verifySignature(
            retrievedSignedGroupParameters
        )

        utxoLedgerGroupParametersPersistenceService.persistIfDoesNotExist(retrievedSignedGroupParameters)
    }

    private fun addUnseenDependenciesToRetrieve(
        retrievedTransaction: UtxoSignedTransaction,
        sortedTransactionIds: TopologicalSort,
        transactionsToRetrieve: LinkedHashSet<SecureHash>
    ) {
        if (retrievedTransaction.id !in sortedTransactionIds.transactionIds) {
            retrievedTransaction.dependencies.let { dependencies ->
                val unseenDependencies = dependencies - sortedTransactionIds.transactionIds
                log.trace {
                    val ignoredDependencies = dependencies - unseenDependencies
                    "Backchain resolution of $initialTransactionIds - Adding dependencies for transaction ${retrievedTransaction.id} " +
                            "dependencies: $unseenDependencies to transactions to retrieve. Ignoring dependencies: $ignoredDependencies " +
                            "as they have already been seen."
                }
                sortedTransactionIds.add(retrievedTransaction.id, dependencies)
                transactionsToRetrieve.addAll(unseenDependencies)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransactionBackchainReceiverFlowV1

        if (initialTransactionIds != other.initialTransactionIds) return false
        if (originalTransactionsToRetrieve != other.originalTransactionsToRetrieve) return false
        if (session != other.session) return false

        return true
    }

    override fun hashCode(): Int {
        var result = initialTransactionIds.hashCode()
        result = 31 * result + originalTransactionsToRetrieve.hashCode()
        result = 31 * result + session.hashCode()
        return result
    }
}