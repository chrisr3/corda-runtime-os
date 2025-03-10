package net.corda.ledger.persistence.utxo.tests.datamodel

import java.time.Instant
import javax.persistence.EntityManagerFactory

class UtxoEntityFactory(entityManagerFactory: EntityManagerFactory) {
    private val entityMap = entityManagerFactory.metamodel.entities.associate { it.name to it.bindableJavaType }

    val utxoTransaction: Class<*> get() = classFor("UtxoTransactionEntity")
    val utxoTransactionComponent: Class<*> get() = classFor("UtxoTransactionComponentEntity")
    val utxoTransactionOutput: Class<*> get() = classFor("UtxoTransactionOutputEntity")
    val utxoVisibleTransactionState: Class<*> get() = classFor("UtxoVisibleTransactionStateEntity")
    val utxoTransactionSource: Class<*> get() = classFor("UtxoTransactionSourceEntity")
    val utxoTransactionStatus: Class<*> get() = classFor("UtxoTransactionStatusEntity")
    val utxoTransactionSignature: Class<*> get() = classFor("UtxoTransactionSignatureEntity")

    fun createUtxoTransactionEntity(
        transactionId: String,
        privacySalt: ByteArray,
        accountId: String,
        created: Instant
    ): Any {
        return utxoTransaction.constructors.single { it.parameterCount == 4 }.newInstance(
            transactionId, privacySalt, accountId, created
        )
    }

    fun createUtxoTransactionComponentEntity(
        utxoTransaction: Any,
        groupIdx: Int,
        leafIdx: Int,
        component: ByteArray,
        hash: String,
        created: Instant
    ): Any {
        return utxoTransactionComponent.constructors.single { it.parameterCount == 6 }.newInstance(
            utxoTransaction, groupIdx, leafIdx, component, hash, created
        )
    }

    fun createUtxoTransactionStatusEntity(
        utxoTransaction: Any,
        status: String,
        created: Instant
    ): Any {
        return utxoTransactionStatus.constructors.single { it.parameterCount == 3 }.newInstance(
            utxoTransaction, status, created
        )
    }

    fun createUtxoTransactionSignatureEntity(
        utxoTransaction: Any,
        signatureIndex: Int,
        signature: ByteArray,
        publicKeyHash: String,
        created: Instant
    ): Any {
        return utxoTransactionSignature.constructors.single { it.parameterCount == 5 }.newInstance(
            utxoTransaction, signatureIndex, signature, publicKeyHash, created
        )
    }

    private fun classFor(entityName: String): Class<*> {
        return entityMap[entityName]
            ?: throw IllegalArgumentException("Unknown entity: '$entityName'")
    }
}
