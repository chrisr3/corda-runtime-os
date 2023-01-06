package net.corda.libs.cpi.datamodel.tests

import net.corda.db.admin.impl.ClassloaderChangeLog
import net.corda.db.admin.impl.LiquibaseSchemaMigratorImpl
import net.corda.db.schema.DbSchema
import net.corda.db.testkit.DbUtils
import net.corda.libs.cpi.datamodel.CpiEntities
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditEntity
import net.corda.libs.cpi.datamodel.CpkDbChangeLogEntity
import net.corda.libs.cpi.datamodel.CpkDbChangeLogKey
import net.corda.libs.cpi.datamodel.changelogAuditEntriesForGivenChangesetIds
import net.corda.libs.cpi.datamodel.findCurrentCpkChangeLogsForCpi
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.orm.EntityManagerConfiguration
import net.corda.orm.impl.EntityManagerFactoryFactoryImpl
import net.corda.orm.utils.transaction
import net.corda.orm.utils.use
import net.corda.v5.crypto.SecureHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import javax.persistence.EntityManager
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditKey
import net.corda.test.util.dsl.entities.cpx.cpi
import net.corda.test.util.dsl.entities.cpx.cpkDbChangeLogAudit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CpkDbChangeLogEntityTest {

    private val dbConfig: EntityManagerConfiguration =
        DbUtils.getEntityManagerConfiguration("cpk_changelog_db")

    private fun transaction(callback: EntityManager.() -> Unit): Unit = EntityManagerFactoryFactoryImpl().create(
        "test_unit",
        CpiEntities.classes.toList(),
        dbConfig
    ).use { em ->
        em.transaction {
            it.callback()
        }
    }

    init {
        val dbChange = ClassloaderChangeLog(
            linkedSetOf(
                ClassloaderChangeLog.ChangeLogResourceFiles(
                    DbSchema::class.java.packageName,
                    listOf("net/corda/db/schema/config/db.changelog-master.xml"),
                    DbSchema::class.java.classLoader
                )
            )
        )
        dbConfig.dataSource.connection.use { connection ->
            LiquibaseSchemaMigratorImpl().updateDb(connection, dbChange)
        }
    }

    @AfterAll
    fun cleanUp() {
        dbConfig.close()
    }

    @Test
    fun `can persist changelogs`() {
        val changesetId = UUID.randomUUID()
        val (cpi, cpks) = TestObject.createCpiWithCpks(2)
        val cpk1 = cpks[0]
        val cpk2 = cpks[1]
        val changeLog1 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk1.id.cpkFileChecksum, "master", changesetId),
            "master-content"
        )
        val changeLog2 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk2.id.cpkFileChecksum, "other", changesetId),
            "other-content"
        )

        transaction {
            persist(cpi)
            persist(changeLog1)
            persist(changeLog2)
        }

        transaction {
            val loadedDbLogEntity = find(
                CpkDbChangeLogEntity::class.java,
                CpkDbChangeLogKey(cpk1.id.cpkFileChecksum, "master", changesetId)
            )

            assertThat(changeLog1.content).isEqualTo(loadedDbLogEntity.content)
        }
    }

    @Test
    fun `can persist changelogs with audit`() {
        val changesetId = UUID.randomUUID()
        val (cpi, cpks) = TestObject.createCpiWithCpks()
        val cpk = cpks.first()
        val cpkFileChecksum = cpk.id.cpkFileChecksum
        val changeLog1 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpkFileChecksum, "master", changesetId),
            "master-content"
        )

        val changeLog1Audit: CpkDbChangeLogAuditEntity = cpkDbChangeLogAuditEntity(cpi.name, cpi.version, cpi.signerSummaryHash, changeLog1)

        transaction {
            persist(cpi)
            persist(changeLog1)
            persist(changeLog1Audit)
        }

        transaction {
            val loadedDbLogEntity = find(
                CpkDbChangeLogEntity::class.java,
                CpkDbChangeLogKey(cpkFileChecksum, "master", changesetId)
            )
            val loadedDbLogAuditEntity = changelogAuditEntriesForGivenChangesetIds(this, setOf(changesetId))
                .singleOrNull()

            assertThat(loadedDbLogAuditEntity)
                .isNotEqualTo(null)
            assertThat(loadedDbLogEntity.id.cpkFileChecksum)
                .isEqualTo(loadedDbLogAuditEntity!!.id.cpkFileChecksum)
        }
    }

    private fun cpkDbChangeLogAuditEntity(cpiName: String, cpiVersion: String, cpiSignerSummaryHash: String, changeLog: CpkDbChangeLogEntity):
            CpkDbChangeLogAuditEntity {
        return CpkDbChangeLogAuditEntity(
            CpkDbChangeLogAuditKey(
                changeLog.id.changesetId,
                changeLog.id.cpkFileChecksum,
                changeLog.id.filePath
            ),
            cpiName,
            cpiVersion,
            cpiSignerSummaryHash,
            changeLog.content,
            changeLog.isDeleted
        )
    }

    @Test
    fun `can update changelogs and add new audit`() {
        val changesetId = UUID.randomUUID()
        val (cpi, cpks) = TestObject.createCpiWithCpks()
        val cpk = cpks.first()
        val changeLog1 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk.id.cpkFileChecksum, "master", changesetId),
            "master-content"
        )

        val changeLog1Audit = cpkDbChangeLogAuditEntity(cpi.name, cpi.version, cpi.signerSummaryHash, changeLog1)

        transaction {
            persist(cpi)
            persist(changeLog1)
            persist(changeLog1Audit)
        }

        transaction {
            val loadedDbLogEntity = find(
                CpkDbChangeLogEntity::class.java,
                CpkDbChangeLogKey(cpk.id.cpkFileChecksum, "master", changesetId)
            )
            loadedDbLogEntity.isDeleted = true
            merge(loadedDbLogEntity)

            merge(cpkDbChangeLogAuditEntity(cpi.name, cpi.version, cpi.signerSummaryHash, loadedDbLogEntity))

        }

        transaction {
            val loadedDbLogAuditEntities = changelogAuditEntriesForGivenChangesetIds(this, setOf(changesetId))
                .sortedBy { it.insertTimestamp }

            assertThat(loadedDbLogAuditEntities).hasSize(1)

            val auditEntity = loadedDbLogAuditEntities[0]
            assertThat(auditEntity.id).isEqualTo(CpkDbChangeLogAuditKey(changesetId, cpk.metadata.cpkFileChecksum, "master"))
            assertThat(auditEntity.cpiName).isEqualTo(cpi.name)
            assertThat(auditEntity.cpiVersion).isEqualTo(cpi.version)
            assertThat(auditEntity.cpiSignerSummaryHash).isEqualTo(cpi.signerSummaryHash)
            assertThat(auditEntity.isDeleted).isEqualTo(true)
        }
    }

    @Test
    fun `persist audit entities and load by query`() {
        val cpkDbChangeLogAudit = cpkDbChangeLogAudit { }
        transaction {
            persist(cpkDbChangeLogAudit)
        }

        transaction {
            val audits = changelogAuditEntriesForGivenChangesetIds(this, setOf(cpkDbChangeLogAudit.id.changesetId))
            assertThat(audits).hasSize(1)
            assertThat(audits[0].id.changesetId).isEqualTo(cpkDbChangeLogAudit.id.changesetId)
            assertThat(audits[0].cpiName).isEqualTo(cpkDbChangeLogAudit.cpiName)
            assertThat(audits[0].cpiVersion).isEqualTo(cpkDbChangeLogAudit.cpiVersion)
            assertThat(audits[0].cpiSignerSummaryHash).isEqualTo(cpkDbChangeLogAudit.cpiSignerSummaryHash)
        }
    }

    @Test
    fun `persist audit entities with multiple changesetIds and load by query`() {
        val rand = UUID.randomUUID()
        val cpiName = "cpiName_$rand"
        val cpiVersion = "cpiVer_$rand"
        val cpiSignerSummaryHash = "cpiSignerSummaryHash_$rand"

        val audit1 = cpkDbChangeLogAudit {
            cpiName(cpiName)
            cpiVersion(cpiVersion)
            cpiSignerSummaryHash(cpiSignerSummaryHash)
            changesetId(UUID.randomUUID())
        }
        val audit2 = cpkDbChangeLogAudit {
            cpiName(cpiName)
            cpiVersion(cpiVersion)
            cpiSignerSummaryHash(cpiSignerSummaryHash)
            changesetId(UUID.randomUUID())
        }
        transaction {
            persist(audit1)
            persist(audit2)
        }

        transaction {
            val audits = changelogAuditEntriesForGivenChangesetIds(this, setOf(audit1.id.changesetId, audit2.id.changesetId))
            assertThat(audits).hasSize(2)
            assertThat(audits.map { it.cpiName }.toSet()).isEqualTo(setOf(cpiName))
            assertThat(audits.map { it.cpiVersion }.toSet()).isEqualTo(setOf(cpiVersion))
            assertThat(audits.map { it.cpiSignerSummaryHash }.toSet()).isEqualTo(setOf(cpiSignerSummaryHash))
            assertThat(audits.map { it.id.changesetId }.toSet()).isEqualTo(setOf(audit1.id.changesetId, audit2.id.changesetId))
        }
    }

    @Test
    fun `when CPI is merged with new CPKs, old orphaned CPK changesets are no longer associated with the CPI`() {
        val changesetId1 = UUID.randomUUID()
        val cpiName = UUID.randomUUID().toString()
        val cpiVersion = UUID.randomUUID().toString()
        val cpiSignerSummaryHash = UUID.randomUUID().toString()
        val originalCpi = cpi {
            name(cpiName)
            version(cpiVersion)
            signerSummaryHash(cpiSignerSummaryHash)
            cpk { }
            cpk { }
            cpk { }
        }
        val originalChangelogs = originalCpi.cpks.mapIndexed { i, cpk ->
            CpkDbChangeLogEntity(
                CpkDbChangeLogKey(cpk.id.cpkFileChecksum, "master-$i", changesetId1),
                "master-content"
            )
        }

        transaction {
            persist(originalCpi)
            originalChangelogs.forEach {
                persist(it)
                persist(cpkDbChangeLogAuditEntity(originalCpi.name, originalCpi.version, originalCpi.signerSummaryHash, it))
            }
        }

        transaction {
            val loadedDbLogAuditEntities = changelogAuditEntriesForGivenChangesetIds(this, setOf(changesetId1))

            assertThat(loadedDbLogAuditEntities.size).isEqualTo(3)
        }

        val changesetId2 = UUID.randomUUID()

        // This CPI has same PK as original, but different set of CPKs. When we merge this on top of original, we will see the old CpiCpk
        // relationships get purged (as a result of orphanRemoval). In the assertion we should see the correct set of dbChangeLogs returned
        val updatedCpi = cpi {
            name(originalCpi.name)
            version(originalCpi.version)
            signerSummaryHash(originalCpi.signerSummaryHash)
            cpk { }
            cpk { }
        }

        val newChangelogs = updatedCpi.cpks.mapIndexed { i, cpiCpk ->
            CpkDbChangeLogEntity(
                CpkDbChangeLogKey(cpiCpk.id.cpkFileChecksum, "master-$i", changesetId2),
                "master-content"
            )
        }

        transaction {
            merge(updatedCpi)
            newChangelogs.forEach {
                persist(it)
                persist(cpkDbChangeLogAuditEntity(updatedCpi.name, updatedCpi.version, updatedCpi.signerSummaryHash, it))
            }
        }

        transaction {
            val loadedDbLogAuditEntities = changelogAuditEntriesForGivenChangesetIds(this, setOf(changesetId2))

            assertThat(loadedDbLogAuditEntities.size).isEqualTo(2)
        }
    }

    @Test
    fun `can persist changelogs to existing CPI`() {
        val changesetId = UUID.randomUUID()
        val (cpi, cpks) = TestObject.createCpiWithCpks()
        val cpk = cpks.first()
        val changeLog1 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk.id.cpkFileChecksum, "master", changesetId),
            "master-content"
        )

        transaction {
            persist(cpi)
        }

        transaction {
            persist(changeLog1)
        }

        transaction {
            val loadedDbLogEntity = find(
                CpkDbChangeLogEntity::class.java,
                CpkDbChangeLogKey(cpk.id.cpkFileChecksum, "master", changesetId)
            )

            assertThat(changeLog1.content).isEqualTo(loadedDbLogEntity.content)
        }
    }

    @Test
    fun `findCpkDbChangeLog returns all for cpk`() {
        val changesetId = UUID.randomUUID()
        val (cpi1, cpks1) = TestObject.createCpiWithCpks(2)
        val (cpi2, cpks2) = TestObject.createCpiWithCpks()
        val cpk1 = cpks1.first()
        val cpk1b = cpks1[1]
        val cpk2 = cpks2.first()

        val changeLog1 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk1.id.cpkFileChecksum, "master", changesetId),
            """<databaseChangeLog xmlns="https://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="https://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.3.xsd">

    <changeSet author="R3.Corda" id="test-migrations-v1.0">
        <createTable tableName="test">
             <column name="id" type="varchar(8)">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>
</databaseChangeLog>"""
        )
        val changeLog1b = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk1b.id.cpkFileChecksum, "master", changesetId),
            """<databaseChangeLog xmlns="https://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="https://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.3.xsd">

    <changeSet author="R3.Corda" id="test-migrations-v1.0">
        <createTable tableName="test">
        <addColumn tableName="person" >
                <column name="is_active" type="varchar2(1)" defaultValue="Y" />  
            </addColumn>  
        </createTable>
    </changeSet>
</databaseChangeLog>"""
        )
        val changeLog2 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk1.id.cpkFileChecksum, "other", changesetId),
            "other-content"
        )
        val changeLog3 = CpkDbChangeLogEntity(
            CpkDbChangeLogKey(cpk2.id.cpkFileChecksum, "master", changesetId),
            "master-content"
        )

        transaction {
            persist(cpi1)
            persist(cpi2)
            persist(changeLog1)
            persist(changeLog1b)
            persist(changeLog2)
            persist(changeLog3)

            val changeLogs = findCurrentCpkChangeLogsForCpi(
                this,
                CpiIdentifier(cpi1.name, cpi1.version, SecureHash.parse(cpi1.signerSummaryHash))
            )
            assertThat(changeLogs.size).isEqualTo(3)
            assertThat(changeLogs.map { it.id }).containsAll(listOf(changeLog1.id, changeLog1b.id, changeLog2.id))
        }
    }

    @Test
    fun `findCpkDbChangeLog with no changelogs`() {
        val (cpi1, _) = TestObject.createCpiWithCpks(2)
        val (cpi2, _) = TestObject.createCpiWithCpks()

        transaction {
            persist(cpi1)
            persist(cpi2)

            val changeLogs = findCurrentCpkChangeLogsForCpi(
                this,
                CpiIdentifier(cpi1.name, cpi1.version, SecureHash("SHA1", cpi1.signerSummaryHash.toByteArray()))
            )
            assertThat(changeLogs).isEmpty()
        }
    }
}
