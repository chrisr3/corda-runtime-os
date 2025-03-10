package com.example.ledger.testing.datamodel.utxo

import java.time.Instant
import javax.persistence.CascadeType
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.JoinColumn
import javax.persistence.JoinTable
import javax.persistence.ManyToMany
import javax.persistence.OneToMany
import javax.persistence.Table
import net.corda.v5.base.annotations.CordaSerializable

@CordaSerializable
@Entity
@Table(name = "utxo_transaction")
data class UtxoTransactionEntity(
    @get:Id
    @get:Column(name = "id", nullable = false, updatable = false)
    var id: String,

    @get:Column(name = "privacy_salt", nullable = false)
    var privacySalt: ByteArray,

    @get:Column(name = "account_id", nullable = false)
    var accountId: String,

    @get:Column(name = "created", nullable = false)
    var created: Instant,
) {
    @get:OneToMany(mappedBy = "transaction", cascade = [CascadeType.ALL], orphanRemoval = true)
    var components: MutableList<UtxoTransactionComponentEntity> = mutableListOf()

    @get:OneToMany(mappedBy = "transaction", cascade = [CascadeType.ALL], orphanRemoval = true)
    var statuses: MutableList<UtxoTransactionStatusEntity> = mutableListOf()

    @get:OneToMany(mappedBy = "transaction", cascade = [CascadeType.ALL], orphanRemoval = true)
    var signatures: MutableList<UtxoTransactionSignatureEntity> = mutableListOf()

    @ManyToMany(cascade = [CascadeType.PERSIST, CascadeType.MERGE])
    @JoinTable(name = "utxo_transaction_cpk",
        joinColumns = [JoinColumn(name = "transaction_id")],
        inverseJoinColumns = [JoinColumn(name = "file_checksum")]
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtxoTransactionEntity

        if (id != other.id) return false
        if (!privacySalt.contentEquals(other.privacySalt)) return false
        if (accountId != other.accountId) return false
        if (created != other.created) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + privacySalt.contentHashCode()
        result = 31 * result + accountId.hashCode()
        result = 31 * result + created.hashCode()
        return result
    }
}
