package net.corda.test.util.dsl.entities.cpx

import java.util.UUID
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditEntity
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditKey

fun cpkDbChangeLogAudit(init: CpkDbChangeLogAuditBuilder.() -> Unit): CpkDbChangeLogAuditEntity {
    val builder = CpkDbChangeLogAuditBuilder()
    init(builder)
    return builder.build()
}

class CpkDbChangeLogAuditBuilder(
    private var cpiNameSupplier: () -> String? = { null },
    private var cpiVersionSupplier: () -> String? = { null },
    private var cpiSignerSummaryHashSupplier: () -> String? = { null },
    private var fileChecksumSupplier: () -> String? = { null },
    private val randomUUID: UUID = UUID.randomUUID()
) {

    private var filePath: String? = null
    private var changesetId: UUID? = null
    private var isDeleted: Boolean? = null

    fun cpiName(value: String): CpkDbChangeLogAuditBuilder {
        cpiNameSupplier = { value }
        return this
    }

    fun cpiVersion(value: String): CpkDbChangeLogAuditBuilder {
        cpiVersionSupplier = { value }
        return this
    }

    fun cpiSignerSummaryHash(value: String): CpkDbChangeLogAuditBuilder {
        cpiSignerSummaryHashSupplier = { value }
        return this
    }

    fun fileChecksum(value: String): CpkDbChangeLogAuditBuilder {
        fileChecksumSupplier = { value }
        return this
    }

    fun filePath(value: String): CpkDbChangeLogAuditBuilder {
        filePath = value
        return this
    }

    fun changesetId(value: UUID): CpkDbChangeLogAuditBuilder {
        changesetId = value
        return this
    }

    fun isDeleted(value: Boolean): CpkDbChangeLogAuditBuilder {
        isDeleted = value
        return this
    }

    fun build(): CpkDbChangeLogAuditEntity {
        return CpkDbChangeLogAuditEntity(
            CpkDbChangeLogAuditKey(
                changesetId ?: UUID.randomUUID(),
                fileChecksumSupplier.invoke() ?: "file_checksum_$randomUUID",
                filePath ?: "file_path_$randomUUID"
            ),
            cpiNameSupplier.invoke() ?: "cpiName_$randomUUID",
            cpiVersionSupplier.invoke() ?: "cpiVersion_$randomUUID",
            cpiSignerSummaryHashSupplier.invoke() ?: "cpiSignerSummaryHash_$randomUUID",
            "data_$randomUUID",
            isDeleted ?: false
        )
    }
}