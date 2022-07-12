package net.corda.libs.packaging.verify.internal.cpk

import net.corda.libs.packaging.PackagingConstants.CPK_BUNDLE_NAME_ATTRIBUTE
import net.corda.libs.packaging.PackagingConstants.CPK_BUNDLE_VERSION_ATTRIBUTE
import net.corda.test.util.InMemoryZipFile
import net.corda.libs.packaging.verify.TestUtils
import net.corda.libs.packaging.verify.TestUtils.addFile
import net.corda.libs.packaging.verify.TestUtils.signedBy
import net.corda.libs.packaging.verify.TestUtils.toBase64
import java.io.ByteArrayInputStream
import java.util.jar.Manifest

internal class TestCpkV2Builder {
    var name = "testCpkV2-1.0.0.0.cpk"
        private set
    var bundleName = "test.cpk"
        private set
    var bundleVersion = "1.0.0.0"
        private set
    var manifest: Manifest? = null
        private set
    var libraries = IntRange(1, 3).map { TestUtils.Library("library$it.jar") }.toTypedArray()
        private set
    var dependencies = emptyArray<TestUtils.Dependency>()
        private set
    var signers = emptyArray<TestUtils.Signer>()
        private set

    fun name(name: String) = apply { this.name = name }
    fun bundleName(bundleName: String) = apply { this.bundleName = bundleName }
    fun bundleVersion(bundleVersion: String) = apply { this.bundleVersion = bundleVersion }
    fun manifest(manifest: Manifest) = apply { this.manifest = manifest }
    fun libraries(vararg libraries: TestUtils.Library) = apply { this.libraries = arrayOf(*libraries) }
    fun dependencies(vararg dependencies: TestUtils.Dependency) = apply { this.dependencies = arrayOf(*dependencies) }
    fun signers(vararg signers: TestUtils.Signer) = apply { this.signers = arrayOf(*signers) }
    fun build() =
        InMemoryZipFile().apply {
            setManifest(manifest ?: cpkV2Manifest())
            addFile("META-INF/CPKDependencies", cpkV2Dependencies(dependencies))
            libraries.forEach { addFile("META-INF/privatelib/${it.name}", it.content) }
            for (i in 1..3) addFile("package/Cpk$i.class")
            addFile("migration/schema-v1.changelog-master.xml")
            addFile("migration/schema.changelog-init.xml")
        }.signedBy(signers = signers)

    private fun cpkV2Manifest() =
        Manifest().apply {
            read(
                ByteArrayInputStream("""
                Manifest-Version: 1.0
                Corda-CPK-Format: 2.0
                $CPK_BUNDLE_NAME_ATTRIBUTE: $bundleName
                $CPK_BUNDLE_VERSION_ATTRIBUTE: $bundleVersion
                """.trimIndent().plus("\n").toByteArray())
            )
        }

    private fun cpkV2Dependencies(dependencies: Array<TestUtils.Dependency>) =
        buildString {
            append("[")
            dependencies.forEachIndexed { i, it ->
                if (i > 0) append(",")
                if (it.hash != null) append(hashDependency(it))
                else append(signerDependency(it))
            }
            append("]")
        }

    private fun hashDependency(dependency: TestUtils.Dependency) =
        """
        {
            "name": "${dependency.name}",
            "version": "${dependency.version}",
            "verifyFileHash": {
                "algorithm": "${dependency.hash!!.algorithm}",
                "fileHash": "${dependency.hash.toBase64()}"
            }
        }
        """.trimIndent().plus("\n")

    private fun signerDependency(dependency: TestUtils.Dependency) =
        """
        {
            "name": "${dependency.name}",
            "version": "${dependency.version}",
            "verifySameSignerAsMe": true
        }
        """.trimIndent().plus("\n")

}
