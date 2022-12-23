package net.corda.chunking.db.impl.tests

import com.google.common.jimfs.Jimfs
import net.corda.chunking.datamodel.ChunkingEntities
import net.corda.chunking.db.impl.persistence.database.DatabaseCpiPersistence
import net.corda.db.admin.impl.ClassloaderChangeLog
import net.corda.db.admin.impl.LiquibaseSchemaMigratorImpl
import net.corda.db.schema.DbSchema
import net.corda.db.testkit.DbUtils
import net.corda.libs.cpi.datamodel.CpiCpkEntity
import net.corda.libs.cpi.datamodel.CpiCpkKey
import net.corda.libs.cpi.datamodel.CpiEntities
import net.corda.libs.cpi.datamodel.CpiMetadataEntity
import net.corda.libs.cpi.datamodel.CpiMetadataEntityKey
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditEntity
import net.corda.libs.cpi.datamodel.CpkDbChangeLogEntity
import net.corda.libs.cpi.datamodel.CpkDbChangeLogKey
import net.corda.libs.cpi.datamodel.CpkFileEntity
import net.corda.libs.cpi.datamodel.CpkMetadataEntity
import net.corda.libs.cpi.datamodel.findCurrentCpkChangeLogsForCpi
import net.corda.libs.packaging.Cpi
import net.corda.libs.packaging.Cpk
import net.corda.libs.packaging.core.CordappManifest
import net.corda.libs.packaging.core.CordappType
import net.corda.libs.packaging.core.CpiIdentifier
import net.corda.libs.packaging.core.CpiMetadata
import net.corda.libs.packaging.core.CpkFormatVersion
import net.corda.libs.packaging.core.CpkIdentifier
import net.corda.libs.packaging.core.CpkManifest
import net.corda.libs.packaging.core.CpkMetadata
import net.corda.libs.packaging.core.CpkType
import net.corda.orm.impl.EntityManagerFactoryFactoryImpl
import net.corda.orm.utils.transaction
import net.corda.v5.crypto.DigestAlgorithmName
import net.corda.v5.crypto.SecureHash
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import java.util.UUID
import javax.persistence.PersistenceException
import net.corda.libs.cpi.datamodel.CpkDbChangeLogAuditKey
import net.corda.libs.cpi.datamodel.getCpiChangelogsForGivenChangesetIds
import net.corda.test.util.dsl.entities.cpx.cpkDbChangeLog

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DatabaseCpiPersistenceTest {
    companion object {
        private const val MIGRATION_FILE_LOCATION = "net/corda/db/schema/config/db.changelog-master.xml"
    }

    // N.B.  We're pulling in the config tables as well.
    private val emConfig = DbUtils.getEntityManagerConfiguration("chunking_db_for_test")
    private val entityManagerFactory = EntityManagerFactoryFactoryImpl().create(
        "test_unit",
        ChunkingEntities.classes.toList() + CpiEntities.classes.toList(),
        emConfig
    )
    private val cpiPersistence = DatabaseCpiPersistence(entityManagerFactory)
    private val mockCpkContent = """
            Lorem ipsum dolor sit amet, consectetur adipiscing elit. Proin id mauris ut tortor 
            condimentum porttitor. Praesent commodo, ipsum vitae malesuada placerat, nisl sem 
            ornare nibh, id rutrum mi elit in metus. Sed ac tincidunt elit. Aliquam quis 
            pellentesque lacus. Quisque commodo tristique pellentesque. Nam sodales, urna id 
            convallis condimentum, nulla lacus vestibulum ipsum, et ultrices sem magna sed neque. 
            Pellentesque id accumsan odio, non interdum nibh. Nullam lacinia vestibulum purus, 
            finibus maximus enim scelerisque eu. Ut nibh lacus, semper eget cursus a, porttitor 
            eu odio. Vivamus vel placerat eros, sed convallis est. Proin tristique ut odio at 
            finibus. 
    """.trimIndent()
    private val mockChangeLogContent = "lorum ipsum"
    /**
     * Creates an in-memory database, applies the relevant migration scripts, and initialises
     * [entityManagerFactory].
     */
    init {
        val dbChange = ClassloaderChangeLog(
            linkedSetOf(
                ClassloaderChangeLog.ChangeLogResourceFiles(
                    DbSchema::class.java.packageName,
                    listOf(MIGRATION_FILE_LOCATION),
                    DbSchema::class.java.classLoader
                )
            )
        )
        emConfig.dataSource.connection.use { connection ->
            LiquibaseSchemaMigratorImpl().updateDb(connection, dbChange)
        }
    }

    @Suppress("Unused")
    @AfterAll
    fun cleanup() {
        emConfig.close()
        entityManagerFactory.close()
    }

    lateinit var fs: FileSystem

    @BeforeEach
    fun beforeEach() {
        fs = Jimfs.newFileSystem()
    }

    @AfterEach
    fun afterEach() {
        fs.close()
    }

    private fun String.writeToPath(): Path {
        val path = fs.getPath(UUID.randomUUID().toString())
        Files.writeString(path, this)
        return path
    }

    private fun updatedCpk(newFileChecksum: SecureHash = newRandomSecureHash(), cpkId: CpkIdentifier) =
        mockCpk(newFileChecksum, cpkId.name, cpkId.version, cpkId.signerSummaryHash)

    private fun mockCpk(
        fileChecksum: SecureHash = newRandomSecureHash(),
        name: String = UUID.randomUUID().toString(),
        version: String = "cpk-version",
        ssh: SecureHash? = newRandomSecureHash()
    ) = mock<Cpk>().also { cpk ->
        val cpkId = CpkIdentifier(
            name = name,
            version = version,
            signerSummaryHash = ssh
        )

        val cpkManifest = CpkManifest(CpkFormatVersion(1, 0))

        val cordappManifest = CordappManifest(
            "", "", -1, -1,
            CordappType.WORKFLOW, "", "", 0, "",
            emptyMap()
        )

        val metadata = CpkMetadata(
            cpkId = cpkId,
            manifest = cpkManifest,
            mainBundle = "main-bundle",
            libraries = emptyList(),
            dependencies = emptyList(),
            cordappManifest = cordappManifest,
            type = CpkType.UNKNOWN,
            fileChecksum = fileChecksum,
            cordappCertificates = emptySet(),
            timestamp = Instant.now()
        )
        whenever(cpk.path).thenReturn(mockCpkContent.writeToPath())
        whenever(cpk.originalFileName).thenReturn("$name.cpk")
        whenever(cpk.metadata).thenReturn(metadata)
    }

    private fun mockCpi(
        vararg cpks: Cpk,
        signerSummaryHash: SecureHash = SecureHash("SHA-256", ByteArray(12)),
        name: String = UUID.randomUUID().toString(),
        version: String = "1.0",
        fileChecksum: SecureHash? = newRandomSecureHash()
    ): Cpi {
        // We need a random name here as the database primary key is (name, version, signerSummaryHash)
        // and we'd end up trying to insert the same mock cpi.
        val id = mock<CpiIdentifier> {
            whenever(it.name).thenReturn(name)
            whenever(it.version).thenReturn(version)
            whenever(it.signerSummaryHash).thenReturn(signerSummaryHash)
        }

        return mockCpiWithId(cpks.toList(), id, fileChecksum)
    }

    private fun mockCpiWithId(
        cpks: List<Cpk>,
        cpiId: CpiIdentifier,
        fileChecksum: SecureHash? = newRandomSecureHash()
    ): Cpi {
        val metadata = mock<CpiMetadata>().also {
            whenever(it.cpiId).thenReturn(cpiId)
            whenever(it.groupPolicy).thenReturn("{}")
            whenever(it.fileChecksum).thenReturn(fileChecksum)
        }

        val cpi = mock<Cpi>().also {
            whenever(it.cpks).thenReturn(cpks.toList())
            whenever(it.metadata).thenReturn(metadata)
        }

        return cpi
    }

    /**
     * Various db tools show a persisted cpk (or bytes) as just a textual 'handle' to the blob of bytes,
     * so explicitly test here that it's actually doing what we think it is (persisting the bytes!).
     */
    @Test
    fun `database cpi persistence writes data and can be read back`() {
        val cpi = mockCpi(mockCpk())
        cpiPersistence.storeWithTestDefaults(cpi)

        val cpkDataEntities: List<CpkFileEntity> = query("fileChecksum", cpi.cpks.first().fileChecksum)
        assertThat(cpkDataEntities.first().data).isEqualTo(mockCpkContent.toByteArray())
    }

    @Test
    fun `database cpi persistence can lookup persisted cpi by checksum`() {
        val cpk = mockCpk()
        assertThat(cpiPersistence.cpkExists(cpk.metadata.fileChecksum)).isFalse
        val cpi = mockCpi(cpk)
        cpiPersistence.storeWithTestDefaults(cpi, "someFileName.cpi")
        assertThat(cpiPersistence.cpkExists(cpk.metadata.fileChecksum)).isTrue
    }

    @Test
    fun `database cpi persistence can write multiple cpks into database`() {
        val cpi = mockCpi(mockCpk(), mockCpk(), mockCpk())
        cpiPersistence.storeWithTestDefaults(cpi)
        assertThrows<PersistenceException> { cpiPersistence.storeWithTestDefaults(cpi) }
    }

    @Test
    fun `database cpi persistence can write multiple CPIs with shared CPKs into database`() {
        val sharedCpk = mockCpk()
        val cpk1 = mockCpk()
        val cpk2 = mockCpk()

        val cpi1 = mockCpi(sharedCpk, cpk1)
        cpiPersistence.storeWithTestDefaults(cpi1)

        val cpi2 = mockCpi(sharedCpk, cpk2)
        assertDoesNotThrow {
            cpiPersistence.storeWithTestDefaults(cpi2)
        }

        // no updates to existing CPKs have occurred hence why all entity versions are 0
        findAndAssertCpks(listOf(Pair(cpi1, sharedCpk), Pair(cpi2, sharedCpk), Pair(cpi1, cpk1), Pair(cpi2, cpk2)))
    }

    @Test
    fun `database cpi persistence can force update a CPI`() {
        val cpk1 = mockCpk()
        val cpi = mockCpi(cpk1)
        val cpiFileName = "test${UUID.randomUUID()}.cpi"

        // first of all, persist the original CPI along with its associated CPKs and a CpkDbChangeLog
        val groupId = "group-a"
        cpiPersistence.persistMetadataAndCpks(
            cpi,
            cpiFileName,
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            groupId,
            listOf(cpkDbChangeLog { fileChecksum(cpk1.fileChecksum) })
        )

        val persistedCpi = loadCpiDirectFromDatabase(cpi)

        // We have persisted a CPK with this CPI, this counts as a version increment on the owning entity, therefore entity version = 1.
        assertThat(persistedCpi.entityVersion).isEqualTo(1)
        assertThat(persistedCpi.cpks.size).isEqualTo(1)
        // The CPK which was merged will have entity version 0.
        assertThat(persistedCpi.cpks.first().entityVersion).isEqualTo(0)

        val cpk2 = mockCpk()
        val updatedCpi = mockCpiWithId(listOf(cpk1, cpk2), cpi.metadata.cpiId)

        // simulate a force update to CPI, including adding two change logs
        cpiPersistence.updateMetadataAndCpks(
            updatedCpi,
            cpiFileName,
            newRandomSecureHash(),
            UUID.randomUUID().toString(),
            groupId,
            listOf(
                cpkDbChangeLog { fileChecksum(cpk1.fileChecksum) },
                cpkDbChangeLog { fileChecksum(cpk2.fileChecksum) }
            )
        )

        val forceUploadedCpi = loadCpiDirectFromDatabase(updatedCpi)

        // optimistic force increment + calling merge on this entity has incremented by 2
        assertThat(forceUploadedCpi.entityVersion).isEqualTo(3)
        assertThat(forceUploadedCpi.cpks.size).isEqualTo(2)
        // cpk1 has incremented because we called merge on the CPI with this entity already existing in the set.
        val forceUploadedCpk1 = forceUploadedCpi.cpks.single { it.id.cpkFileChecksum == cpk1.fileChecksum }
        assertThat(forceUploadedCpk1.entityVersion).isEqualTo(1)
        assertThat(forceUploadedCpk1.metadata.entityVersion).isEqualTo(0)
        val forceUploadedCpk2 = forceUploadedCpi.cpks.single { it.id.cpkFileChecksum == cpk2.fileChecksum }
        assertThat(forceUploadedCpk2.entityVersion).isEqualTo(0)
        assertThat(forceUploadedCpk2.metadata.entityVersion).isEqualTo(0)

//        assertChangeLogPersistedWithCpi(cpk1)
//        assertChangeLogPersistedWithCpi(cpk2)
    }

    private fun assertChangeLogPersistedWithCpi(cpk: Cpk) {
        val cpkFileChecksum = cpk.metadata.fileChecksum.toString()
        val dbChangeLog = loadCpkDbChangeLog(cpkFileChecksum, cpk.path.toString())
        assertThat(dbChangeLog.id.cpkFileChecksum).isEqualTo(cpkFileChecksum)
    }

    private fun loadCpkDbChangeLog(cpkFileChecksum: String, filePath: String): CpkDbChangeLogEntity {
        return entityManagerFactory.createEntityManager().transaction { em ->
            em.find(CpkDbChangeLogEntity::class.java, CpkDbChangeLogKey(cpkFileChecksum, filePath))
        }
    }

    @Test
    fun `database cpi persistence can force update the same CPI`() {
        val cpiChecksum = newRandomSecureHash()
        val cpi = mockCpi(mockCpk())

        cpiPersistence.persistMetadataAndCpksWithDefaults(cpi, "test.cpi", cpiChecksum)

        val loadedCpi = loadCpiDirectFromDatabase(cpi)

        // adding cpk to cpi accounts for 1 modification
        assertThat(loadedCpi.entityVersion).isEqualTo(1)
        assertThat(loadedCpi.cpks.size).isEqualTo(1)
        assertThat(loadedCpi.cpks.first().entityVersion).isEqualTo(0)

        cpiPersistence.updateMetadataAndCpksWithDefaults(
            cpi,
            cpiFileChecksum = cpiChecksum
        )  // force update same CPI

        val updatedCpi = loadCpiDirectFromDatabase(cpi)

        assertThat(updatedCpi.insertTimestamp).isAfter(loadedCpi.insertTimestamp)
        // merging updated cpi accounts for 1 modification + modifying cpk
        assertThat(updatedCpi.entityVersion).isEqualTo(3)
        assertThat(updatedCpi.cpks.size).isEqualTo(1)
        // merging on cpi with a changed set of cpks results in increment to any existing cpks in the set (event if they are unchanged)
        assertThat(updatedCpi.cpks.first().entityVersion).isEqualTo(1)
    }

    @Test
    fun `CPKs are correct after persisting a CPI with already existing CPK`() {
        val sharedCpk = mockCpk()
        val cpi = mockCpi(sharedCpk)
        cpiPersistence.storeWithTestDefaults(cpi, groupId = "group-a")
        val cpi2 = mockCpi(sharedCpk)
        cpiPersistence.storeWithTestDefaults(cpi2, cpiFileName = "test2.cpi", groupId = "group-b")
        // no updates to existing CPKs have occurred hence why all entity versions are 0
        findAndAssertCpks(listOf(Pair(cpi, sharedCpk), Pair(cpi2, sharedCpk)))
    }

    @Test
    fun `CPKs are correct after updating a CPI by adding a new CPK`() {
        val cpk1 = mockCpk()
        val cpi = mockCpi(cpk1)
        cpiPersistence.persistMetadataAndCpksWithDefaults(cpi, groupId = "group-a")
        // a new cpi object, but with same ID and added new CPK
        val cpk2 = mockCpk()
        val updatedCpi = mockCpiWithId(listOf(cpk1, cpk2), cpi.metadata.cpiId)
        cpiPersistence.updateMetadataAndCpksWithDefaults(updatedCpi, groupId = "group-b")
        assertThat(cpi.metadata.cpiId).isEqualTo(updatedCpi.metadata.cpiId)

        findAndAssertCpks(listOf(Pair(cpi, cpk1)), expectedCpiCpkEntityVersion = 1) // incremented as we merged during force.
        findAndAssertCpks(listOf(Pair(cpi, cpk2)))
    }

    @Test
    fun `update CPI replacing its CPK with a new one with new file checksum`() {
        val cpk = mockCpk()
        val cpi = mockCpi(cpk)
        val newChecksum = newRandomSecureHash()
        val updatedCpk = updatedCpk(newChecksum, cpk.metadata.cpkId)
        cpiPersistence.persistMetadataAndCpksWithDefaults(cpi, groupId = "group-a")
        val updatedCpi = mockCpiWithId(listOf(updatedCpk), cpi.metadata.cpiId)  // a new cpi object, but with same ID
        cpiPersistence.updateMetadataAndCpksWithDefaults(updatedCpi, groupId = "group-b")
        assertThat(cpi.metadata.cpiId).isEqualTo(updatedCpi.metadata.cpiId)

        assertCpkIsNotAssociatedWithCpi(cpi, cpk)

        findAndAssertCpks(
            listOf(Pair(cpi, updatedCpk)),
            expectedCpkFileChecksum = newChecksum.toString()
        )
    }

    @Test
    fun `multiple CPKs with the same name, version, ssh but different checksum are allowed in a CPI`() {
        val rand = UUID.randomUUID()
        val cpkName = "name_$rand"
        val cpkVersion = "version_$rand"
        val cpkSsh = newRandomSecureHash()
        val cpkFileChecksum1 = newRandomSecureHash()
        val cpkFileChecksum2 = newRandomSecureHash()

        val cpk1 = mockCpk(cpkFileChecksum1, cpkName, cpkVersion, cpkSsh)
        val cpk2 = mockCpk(cpkFileChecksum2, cpkName, cpkVersion, cpkSsh)
        val cpi = mockCpi(cpk1, cpk2)

        cpiPersistence.persistMetadataAndCpksWithDefaults(cpi, groupId = "group-a")

        findAndAssertCpks(
            listOf(Pair(cpi, cpk1)),
            expectedCpkFileChecksum = cpkFileChecksum1.toString()
        )
        findAndAssertCpks(
            listOf(Pair(cpi, cpk2)),
            expectedCpkFileChecksum = cpkFileChecksum2.toString()
        )
    }

    @Test
    fun `CPK version is incremented when CpiCpkEntity has non-zero entityversion`() {
        val cpk1 = mockCpk()
        val cpi = mockCpi(cpk1)
        cpiPersistence.persistMetadataAndCpksWithDefaults(cpi, groupId = "group-a")
        findAndAssertCpks(listOf(Pair(cpi, cpk1)))

        // a new cpi object, but with same cpk
        val secondCpkChecksum = newRandomSecureHash()
        val updatedCpk = updatedCpk(secondCpkChecksum, cpi.cpks.first().metadata.cpkId)
        val updatedCpi = mockCpiWithId(listOf(updatedCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpksWithDefaults(updatedCpi, groupId = "group-b")

        assertCpkIsNotAssociatedWithCpi(cpi, cpk1)

        // new CPK entity hence why other CPKs no longer associated, this one has entity versions of 0 as CPKs are not updated
        findAndAssertCpks(
            listOf(Pair(cpi, updatedCpk)),
            expectedCpkFileChecksum = updatedCpk.fileChecksum,
        )

        // a new cpi object, but with same cpk
        val thirdChecksum = newRandomSecureHash()
        val anotherUpdatedCpk = updatedCpk(thirdChecksum, cpi.cpks.first().metadata.cpkId)
        val anotherUpdatedCpi = mockCpiWithId(listOf(anotherUpdatedCpk), cpi.metadata.cpiId)

        cpiPersistence.updateMetadataAndCpksWithDefaults(anotherUpdatedCpi, groupId = "group-b")

        assertCpkIsNotAssociatedWithCpi(cpi, updatedCpk)

        // new CPK entity hence why other CPKs no longer associated, this one has entity versions of 0 as CPKs are not updated
        findAndAssertCpks(
            listOf(Pair(cpi, anotherUpdatedCpk)),
            expectedCpkFileChecksum = thirdChecksum.toString(),
        )
    }

    @Test
    fun `force upload can remove all changelogs`() {
        val cpk1 = mockCpk()
        val cpiWithChangelogs = mockCpi(cpk1)

        val cpiEntity = cpiPersistence.persistMetadataAndCpksWithDefaults(
            cpiWithChangelogs,
            cpkDbChangeLogEntities = listOf(
                cpkDbChangeLog {
                    fileChecksum(cpk1.fileChecksum)
                }
            )
        )

        val changelogsWith = findChangelogs(cpiEntity)
        assertThat(changelogsWith.size).isEqualTo(1)

        val updatedCpi = mockCpiWithId(listOf(mockCpk()), cpiWithChangelogs.metadata.cpiId)

        // no change sets in this CPK
        val updateCpiEntity = cpiPersistence.updateMetadataAndCpksWithDefaults(updatedCpi)

        val changelogsWithout = findChangelogs(updateCpiEntity)
        assertThat(changelogsWithout.size).isEqualTo(0)
    }

    @Test
    fun `cannot store multiple versions of the same CPI name in the same group`() {
        val name = UUID.randomUUID().toString()
        val cpiV1 = mockCpi(mockCpk(), name = name, version = "v1")
        val cpiV2 = mockCpi(mockCpk(), name = name, version = "v2")
        val cpiEntityV1 = cpiPersistence.storeWithTestDefaults(cpiV1)
        val cpiEntityV2 = cpiPersistence.storeWithTestDefaults(cpiV2)
        assertThat(cpiEntityV1.name).isEqualTo(name)
        assertThat(cpiEntityV1.version).isEqualTo("v1")
        assertThat(cpiEntityV1.cpks).hasSize(1)
        assertThat(cpiEntityV2.name).isEqualTo(name)
        assertThat(cpiEntityV2.version).isEqualTo("v2")
        assertThat(cpiEntityV2.cpks).hasSize(1)
    }

    @Test
    fun `force upload adds a new changelog audit entry`() {
        val cpk1 = mockCpk()
        val cpi = mockCpi(cpk1)
        val rand1 = UUID.randomUUID()
        val changesetFilePath = "path_1_$rand1.xml"
        val cpiEntity = cpiPersistence.persistMetadataAndCpksWithDefaults(
            cpi,
            cpkDbChangeLogEntities = listOf(
                cpkDbChangeLog {
                    fileChecksum(cpk1.metadata.fileChecksum.toString())
                    filePath(changesetFilePath)
                    changesetId(rand1)
                }
            )
        )

        val changelogs = findChangelogs(cpiEntity)
        val changelogAudits = findChangelogAudits(cpiEntity, setOf(rand1))
        assertThat(changelogs.size).isEqualTo(1)
        assertThat(changelogAudits.size).isEqualTo(1)

        val cpk2 = mockCpk()
        val updatedCpi = mockCpiWithId(listOf(cpk2), cpi.metadata.cpiId)

        // we're updating a CPI with a new CPK and a new CPK change log
        val rand2 = UUID.randomUUID()
        val updateCpiEntity = cpiPersistence.updateMetadataAndCpksWithDefaults(
            updatedCpi,
            cpkDbChangeLogEntities = listOf(
                cpkDbChangeLog {
                    fileChecksum(cpk2.metadata.fileChecksum.toString())
                    filePath(changesetFilePath)
                    changesetId(rand2)
                }
            )
        )
        val updatedChangelogs = findChangelogs(updateCpiEntity)
        val updatedChangelogAudits = findChangelogAudits(updateCpiEntity, setOf(rand1, rand2))

        assertThat(updatedChangelogs.size)
            .withFailMessage("Expecting only 1 changelog to be associated with the CPI as only one CPK is associated")
            .isEqualTo(1)
        assertThat(updatedChangelogAudits.size)
            .withFailMessage("Expecting 2 changelog audit records since a virtual node may have run migration from the " +
                    "previous CPK, and the content of this changelog should be persisted in an audit accessible via the cpi identifier " +
                    "and changeset ID")
            .isEqualTo(2)
        assertThat((changelogs + updatedChangelogs).map { cpkDbChangeLogAuditEntity(cpi.metadata.cpiId, it).id })
            .containsAll(updatedChangelogAudits.map { it.id })
    }

    private fun findChangelogs(cpiEntity: CpiMetadataEntity) = entityManagerFactory.createEntityManager().transaction {
        findCurrentCpkChangeLogsForCpi(
            it,
            CpiIdentifier(
                name = cpiEntity.name,
                version = cpiEntity.version,
                signerSummaryHash = SecureHash.parse(cpiEntity.signerSummaryHash)
            )
        )
    }

    private fun findChangelogAudits(cpiEntity: CpiMetadataEntity, changesetIds: Set<UUID>) = entityManagerFactory.createEntityManager().transaction {
        getCpiChangelogsForGivenChangesetIds(
            it,
            cpiEntity.name,
            cpiEntity.version,
            cpiEntity.signerSummaryHash,
            changesetIds
        )
    }

    private fun cpkDbChangeLogAuditEntity(cpiIdentifier: CpiIdentifier, changelog: CpkDbChangeLogEntity): CpkDbChangeLogAuditEntity {
        return CpkDbChangeLogAuditEntity(
            CpkDbChangeLogAuditKey(
                cpiIdentifier.name,
                cpiIdentifier.version,
                cpiIdentifier.signerSummaryHash?.toString() ?: "",
                changelog.id.cpkFileChecksum,
                changelog.changesetId,
                changelog.entityVersion,
                changelog.id.filePath
            ),
            changelog.content,
            changelog.isDeleted
        )
    }

    @Test
    fun `force upload adds multiple changelog audit entry for multiple changesets`() {
        val cpk = mockCpk()
        val cpi = mockCpi(cpk)
        val rand1 = UUID.randomUUID()
        val cpiEntity = cpiPersistence.persistMetadataAndCpksWithDefaults(
            cpi,
            cpkDbChangeLogEntities = listOf(
                cpkDbChangeLog {
                    fileChecksum(cpk.metadata.fileChecksum.toString())
                    changesetId(rand1)
                }
            )
        )

        val changelogs = findChangelogs(cpiEntity)
        val changelogAudits = findChangelogAudits(cpiEntity, setOf(rand1))
        assertThat(changelogs.size).isEqualTo(1)
        assertThat(changelogAudits.size).isEqualTo(1)

        val cpk2 = mockCpk()
        val updatedCpi = mockCpiWithId(listOf(cpk2), cpi.metadata.cpiId)
        val cpk2FileChecksum = cpk2.metadata.fileChecksum.toString()
        val rand2 = UUID.randomUUID()
        val changeset1FileName = "changeset1_$rand2.xml"
        val changeset2FileName = "changeset2_$rand2.xml"

        val updateCpiEntity = cpiPersistence.updateMetadataAndCpksWithDefaults(
            updatedCpi,
            cpkDbChangeLogEntities = listOf(
                cpkDbChangeLog {
                    fileChecksum(cpk2FileChecksum)
                    filePath(changeset1FileName)
                    changesetId(rand2)
                },
                cpkDbChangeLog {
                    fileChecksum(cpk2FileChecksum)
                    filePath(changeset2FileName)
                    changesetId(rand2)
                },
            )
        )

        val updatedChangelogs = findChangelogs(updateCpiEntity)
        val updatedChangelogAudits = findChangelogAudits(updateCpiEntity, setOf(rand1, rand2))

        assertThat(updatedChangelogs.size).isEqualTo(2)
        assertThat(updatedChangelogAudits.size).isEqualTo(3)
        assertThat((changelogs + updatedChangelogs).map { cpkDbChangeLogAuditEntity(cpi.metadata.cpiId, it).id })
            .containsAll(updatedChangelogAudits.map { it.id })
    }

    @Test
    fun `persist changelog writes data and can be read back`() {
        val cpi = mockCpi(mockCpk())
        cpiPersistence.storeWithTestDefaults(cpi, cpkDbChangeLogEntities = makeChangeLogs(arrayOf(cpi.cpks.first())))

        val changeLogsRetrieved = query<CpkDbChangeLogEntity, String>(
            "cpk_file_checksum",
            cpi.cpks.first().metadata.fileChecksum.toString()
        )

        assertThat(changeLogsRetrieved.size).isGreaterThanOrEqualTo(1)
        assertThat(changeLogsRetrieved.first().content).isEqualTo(mockChangeLogContent)
    }

    @Test
    fun `persist multiple changelogs writes data and can be read back`() {
        val cpi = mockCpi(mockCpk(), mockCpk(), mockCpk(), mockCpk(), mockCpk())
        cpiPersistence.storeWithTestDefaults(cpi, cpkDbChangeLogEntities = makeChangeLogs(cpi.cpks.toTypedArray()))

        val changeLogsRetrieved = query<CpkDbChangeLogEntity, String>("content", mockChangeLogContent)

        assertThat(changeLogsRetrieved.size).isGreaterThanOrEqualTo(5)
        assertThat(changeLogsRetrieved.first().content).isEqualTo(mockChangeLogContent)
    }

    private fun assertCpkIsNotAssociatedWithCpi(cpi: Cpi, cpk: Cpk) {
        entityManagerFactory.createEntityManager().transaction { em ->
            assertThat(
                em.find(
                    CpiCpkEntity::class.java,
                    CpiCpkKey(
                        cpi.metadata.cpiId.name,
                        cpi.metadata.cpiId.version,
                        cpi.metadata.cpiId.signerSummaryHash.toString(),
                        cpk.fileChecksum
                    )
                )
            ).isNull()
        }
    }

    private inline fun <reified T : Any, K> query(key: String, value: K): List<T> {
        val query = "FROM ${T::class.simpleName} where $key = :value"
        return entityManagerFactory.createEntityManager().transaction {
            it.createQuery(query, T::class.java)
                .setParameter("value", value)
                .resultList
        }!!
    }
    
    private val random = Random(0)
    private fun newRandomSecureHash(): SecureHash {
        return SecureHash(DigestAlgorithmName.DEFAULT_ALGORITHM_NAME.name, ByteArray(32).also(random::nextBytes))
    }

    private fun makeChangeLogs(
        cpks: Array<Cpk>,
        changeLogs: List<String> = listOf(mockChangeLogContent)
    ): List<CpkDbChangeLogEntity> = cpks.flatMap { cpk ->
        changeLogs.map { changeLog ->
            CpkDbChangeLogEntity(
                CpkDbChangeLogKey(
                    cpk.metadata.fileChecksum.toString(),
                    "resources/$changeLog"
                ),
                changeLog,
                UUID.randomUUID()
            )
        }
    }

    private fun loadCpiDirectFromDatabase(cpi: Cpi): CpiMetadataEntity =
        entityManagerFactory.createEntityManager().transaction {
            it.find(
                CpiMetadataEntity::class.java, CpiMetadataEntityKey(
                    cpi.metadata.cpiId.name,
                    cpi.metadata.cpiId.version,
                    cpi.metadata.cpiId.signerSummaryHash.toString(),
                )
            )
        }!!

    private fun findAndAssertCpks(
        combos: List<Pair<Cpi, Cpk>>,
        expectedCpkFileChecksum: String? = null,
        expectedMetadataEntityVersion: Int = 0,
        expectedFileEntityVersion: Int = 0,
        expectedCpiCpkEntityVersion: Int = 0
    ) {
        combos.forEach { (cpi, cpk) ->
            val (cpkMetadata, cpkFile, cpiCpk) = entityManagerFactory.createEntityManager().transaction {
                val cpiCpkKey = CpiCpkKey(
                    cpi.metadata.cpiId.name,
                    cpi.metadata.cpiId.version,
                    cpi.metadata.cpiId.signerSummaryHash.toString(),
                    cpk.metadata.fileChecksum.toString()
                )
                val cpkKey = cpk.metadata.fileChecksum.toString()
                val cpiCpk = it.find(CpiCpkEntity::class.java, cpiCpkKey)
                val cpkMetadata = it.find(CpkMetadataEntity::class.java, cpkKey)
                val cpkFile = it.find(CpkFileEntity::class.java, cpkKey)
                Triple(cpkMetadata, cpkFile, cpiCpk)
            }

            assertThat(cpkMetadata.cpkFileChecksum).isEqualTo(expectedCpkFileChecksum ?: cpk.fileChecksum)
            assertThat(cpkFile.fileChecksum).isEqualTo(expectedCpkFileChecksum ?: cpk.fileChecksum)

            assertThat(cpkMetadata.entityVersion)
                .withFailMessage("CpkMetadataEntity.entityVersion expected $expectedMetadataEntityVersion but was ${cpkMetadata.entityVersion}.")
                .isEqualTo(expectedMetadataEntityVersion)
            assertThat(cpkFile.entityVersion)
                .withFailMessage("CpkFileEntity.entityVersion expected $expectedFileEntityVersion but was ${cpkFile.entityVersion}.")
                .isEqualTo(expectedFileEntityVersion)
            assertThat(cpiCpk.entityVersion)
                .withFailMessage("CpiCpkEntity.entityVersion expected $expectedCpiCpkEntityVersion but was ${cpiCpk.entityVersion}.")
                .isEqualTo(expectedCpiCpkEntityVersion)
        }
    }
}

