package net.corda.cli.plugins.packaging.signing

import jdk.security.jarsigner.JarSigner
import net.corda.cli.plugins.packaging.aws.kms.KmsProvider
import net.corda.cli.plugins.packaging.closeKmsClient
import net.corda.cli.plugins.packaging.getECPrivateKey
import net.corda.cli.plugins.packaging.getKmsClient
import net.corda.cli.plugins.packaging.getRSAPrivateKey
import net.corda.cli.plugins.packaging.isRsaKeyType
import net.corda.libs.packaging.verify.SigningHelpers.isSigningRelated
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

internal object SigningHelpers {
    /**
     * Signs Cpx jar files.
     */
    @Suppress("LongParameterList")
    fun sign(
        unsignedInputCpx: Path,
        signedOutputCpx: Path,
        keyStoreFileName: String,
        keyStorePass: String,
        keyAlias: String,
        signerName: String,
        tsaUrl: String?
    ) {
        ZipFile(unsignedInputCpx.toFile()).use { unsignedCpi ->
            Files.newOutputStream(signedOutputCpx,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW
            ).use { signedCpi ->

                val privateKeyEntry = getPrivateKeyEntry(
                    keyStoreFileName,
                    keyStorePass,
                    keyAlias
                )
                val privateKey = privateKeyEntry.privateKey
                val certPath = buildCertPath(privateKeyEntry.certificateChain.asList())

                // Create JarSigner
                val builder = JarSigner.Builder(privateKey, certPath)
                    .signerName(signerName)

                // Use timestamp server if provided
                tsaUrl?.let { builder.tsa(URI(it)) }

                // Sign CPI
                builder
                    .build()
                    .sign(unsignedCpi, signedCpi)
            }
        }
    }

    @Suppress("LongParameterList")
    fun signWithKms(
        unsignedInputCpx: Path,
        signedOutputCpx: Path,
        certChain: Path,
        keyId: String,
        signerName: String,
        tsaUrl: String?
    ) {
        ZipFile(unsignedInputCpx.toFile()).use { unsignedCpi ->
            Files.newOutputStream(
                signedOutputCpx,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW
            ).use { signedCpi ->

                val kmsClient = getKmsClient()
                val kmsProvider = KmsProvider(kmsClient)
                Security.addProvider(kmsProvider) // It is important to register the provider!

                val keyIsRsa = isRsaKeyType(kmsClient, keyId)
                val privateKey = if (keyIsRsa) { getRSAPrivateKey(keyId) } else { getECPrivateKey(keyId) }
                val jarsignerSignatureAlgorithm = if (keyIsRsa) { "SHA256withRSA" } else { "SHA256withECDSA" }

                val certChainString = convertFileToX509Cert(certChain)
                val certificateChain = listOf(certChainString)
                val certPath = buildCertPath(certificateChain)

                // Create JarSigner
                val builder = JarSigner.Builder(privateKey, certPath)
                    .signerName(signerName)
                    .signatureAlgorithm(jarsignerSignatureAlgorithm, kmsProvider)

                // Use timestamp server if provided
                tsaUrl?.let { builder.tsa(URI(it)) }

                // Sign CPx
                builder
                    .build()
                    .sign(unsignedCpi, signedCpi)

                closeKmsClient(kmsClient)
            }
        }
    }

    @Suppress("NestedBlockDepth")
    fun removeSignatures(signedCpx: Path, removedSignaturesCpx: Path) {
        JarInputStream(Files.newInputStream(signedCpx, StandardOpenOption.READ)).use { inputJar ->
            val manifest = Manifest()
            // Leave out manifest signature entries, so only get main attributes.
            manifest.mainAttributes.putAll(inputJar.manifest.mainAttributes)
            JarOutputStream(Files.newOutputStream(removedSignaturesCpx, StandardOpenOption.WRITE), manifest).use { outputJar ->
                var nextEntry = inputJar.nextJarEntry
                while (nextEntry != null) {
                    if (!isSigningRelated(nextEntry)) {
                        outputJar.putNextEntry(nextEntry)
                        inputJar.copyTo(outputJar)
                        outputJar.closeEntry()
                    }
                    nextEntry = inputJar.nextJarEntry
                }
            }
        }
    }

    /**
     * Reads PrivateKeyEntry from key store
     */
    private fun getPrivateKeyEntry(keyStoreFileName: String, keyStorePass: String, keyAlias: String): KeyStore.PrivateKeyEntry {
        val passwordCharArray = keyStorePass.toCharArray()
        val keyStore = KeyStore.getInstance(File(keyStoreFileName), passwordCharArray)

        when (val keyEntry = keyStore.getEntry(keyAlias, KeyStore.PasswordProtection(passwordCharArray))) {
            is KeyStore.PrivateKeyEntry -> return keyEntry
            else -> error("Alias \"${keyAlias}\" is not a private key")
        }
    }

    /**
     * Type of CertificateFactory to use. "X.509" is a required type for Java implementations.
     */
    private const val STANDARD_CERT_FACTORY_TYPE = "X.509"

    /**
     * Builds CertPath from certificate chain
     */
    private fun buildCertPath(certificateChain: List<Certificate>) =
        CertificateFactory
            .getInstance(STANDARD_CERT_FACTORY_TYPE)
            .generateCertPath(certificateChain)

    private fun convertFileToX509Cert(certificate: Path): X509Certificate {
        val targetStream: InputStream = File(certificate.toUri()).inputStream()
        return CertificateFactory
            .getInstance(STANDARD_CERT_FACTORY_TYPE)
            .generateCertificate(targetStream) as X509Certificate
    }
}
