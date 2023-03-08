package net.corda.crypto.softhsm.impl

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.cache.caffeine.CacheFactoryImpl
import net.corda.cipher.suite.impl.CipherSchemeMetadataImpl
import net.corda.crypto.cipher.suite.KeyGenerationSpec
import net.corda.crypto.cipher.suite.KeyMaterialSpec
import net.corda.crypto.core.aes.WrappingKey
import net.corda.crypto.core.aes.WrappingKeyImpl
import net.corda.crypto.persistence.WrappingKeyInfo
import net.corda.crypto.softhsm.impl.infra.CountingWrappingKey
import net.corda.crypto.softhsm.impl.infra.TestWrappingKeyStore
import net.corda.v5.crypto.KeySchemeCodes.RSA_CODE_NAME
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import java.security.PrivateKey
import java.security.PublicKey
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Testing of the crypto service with caching.
 *
 * There is no way of testing the eviction in timely manner or without having special setup for the cache during
 * testing as by default the eviction is scheduled on an executor, so it's not exactly deterministic,.
 */

class SoftCryptoServiceCachingTests {
    val schemeMetadata = CipherSchemeMetadataImpl()

    val unwrapCount = AtomicInteger()
    val rootWrappingKey = CountingWrappingKey(WrappingKeyImpl.generateWrappingKey(schemeMetadata), unwrapCount)
    
    fun makePrivateKeyCache(): Cache<PublicKey, PrivateKey> = CacheFactoryImpl().build(
        "test private key cache", Caffeine.newBuilder()
            .expireAfterAccess(3600, TimeUnit.MINUTES)
            .maximumSize(3)
    )

    private fun makeWrappingKeyCache(): Cache<String, WrappingKey> = CacheFactoryImpl().build(
        "test wrapping key cache", Caffeine.newBuilder()
            .expireAfterAccess(3600, TimeUnit.MINUTES)
            .maximumSize(100)
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `getPrivateKey should cache requested key using public key as cache key`(cachePrivateKeys: Boolean) {
        val privateKeyCache = if (cachePrivateKeys) makePrivateKeyCache() else null
        val myCryptoService = SoftCryptoService(
            TestWrappingKeyStore(mock()),
            schemeMetadata,
            rootWrappingKey,
            makeWrappingKeyCache(),
            privateKeyCache
        )
        val scheme = myCryptoService.supportedSchemes.filter { it.key.codeName == RSA_CODE_NAME }.toList().first().first
        myCryptoService.createWrappingKey("master-alias", true, emptyMap())
        val key1 = myCryptoService.generateKeyPair(KeyGenerationSpec(scheme, "key-1", "master-alias"), emptyMap())
        val key2 = myCryptoService.generateKeyPair(KeyGenerationSpec(scheme, "key-2", "master-alias"), emptyMap())
        val privateKey1 = privateKeyCache?.getIfPresent(key1.publicKey)
        val privateKey2 = privateKeyCache?.getIfPresent(key2.publicKey)
        privateKeyCache?.invalidate(key1.publicKey)
        privateKeyCache?.invalidate(key2.publicKey)
        val key1Spec = KeyMaterialSpec(key1.keyMaterial, "master-alias", key1.encodingVersion)
        val key2Spec = KeyMaterialSpec(key2.keyMaterial, "master-alias", key2.encodingVersion)
        val key11 = myCryptoService.getPrivateKey(key1.publicKey, key1Spec)
        val key21 = myCryptoService.getPrivateKey(key2.publicKey, key2Spec)
        val key12 = myCryptoService.getPrivateKey(key1.publicKey, key1Spec)
        val key22 = myCryptoService.getPrivateKey(key2.publicKey, key2Spec)
        assertNotSame(key11, key21)
        assertNotSame(key12, key22)
        if (cachePrivateKeys) {
            assertSame(key11, key12)
            assertSame(key21, key22)
        } else {
            // without caching we generally expect key11 and key12 to be different objects, but 
            // it seems they can sometimes be the same, which suggests that caffine even with cache size set to 0
            // sometimes can cache for a short period. So if we have assertNotSame(key11, key12) here it can fail.
            assertEquals(key11, key12)
            assertEquals(key21, key22)
        }
        // the keys we pulled out are reconstructed from encrypted key material, so are
        // not the same objects but are equal
        if (privateKey1 != null) {
            assertNotSame(key11, privateKey1)
            assertEquals(key11, privateKey1)
        }
        if (privateKey2 != null) {
            assertNotSame(key22, privateKey2)
            assertEquals(key21, privateKey2)
        }
        Assertions.assertThat(myCryptoService.getUnwrapCounter()).isEqualTo(if (cachePrivateKeys) 2 else 4)
    }


    @Test
    fun `wrapPrivateKey should put to cache using public key as cache key`() {
        val myCryptoService = SoftCryptoService(
            TestWrappingKeyStore(mock()),
            schemeMetadata,
            rootWrappingKey,
            makeWrappingKeyCache(),
            makePrivateKeyCache()
        )
        myCryptoService.createWrappingKey("master-alias", true, emptyMap())
        val scheme = myCryptoService.supportedSchemes.filter { it.key.codeName == RSA_CODE_NAME }.toList().first().first
        val key = myCryptoService.generateKeyPair(KeyGenerationSpec(scheme, "key-1", "master-alias"), emptyMap())
        val keySpec = KeyMaterialSpec(key.keyMaterial, "master-alias", key.encodingVersion)
        myCryptoService.getPrivateKey(key.publicKey, keySpec)
        assertThat(myCryptoService.getUnwrapCounter()).isEqualTo(0)
        assertThat(myCryptoService.getWrapCounter()).isEqualTo(1)
    }

    @Test
    fun `wrappingKeyExists should return true whenever key exist in cache and false otherwise`() {
        val wrappingKeyCache = makeWrappingKeyCache()
        val knownWrappingKey = WrappingKeyImpl.generateWrappingKey(schemeMetadata)
        val myCryptoService = SoftCryptoService(
            TestWrappingKeyStore(mock()),
            schemeMetadata,
            rootWrappingKey,
            wrappingKeyCache,
            makePrivateKeyCache()
        )

        val cacheAlias = UUID.randomUUID().toString()
        val unknownAlias = UUID.randomUUID().toString()
        assertFalse(myCryptoService.wrappingKeyExists(cacheAlias))
        assertFalse(myCryptoService.wrappingKeyExists(unknownAlias))
        wrappingKeyCache.put(cacheAlias, knownWrappingKey)
        assertTrue(myCryptoService.wrappingKeyExists(cacheAlias))
        assertFalse(myCryptoService.wrappingKeyExists(unknownAlias))
        assertTrue(myCryptoService.wrappingKeyExists(cacheAlias))
    }


    @Test
    fun `createWrappingKey should put to cache using public key as cache key`() {
        val schemeMetadata = CipherSchemeMetadataImpl()
        val rootWrappingKey = WrappingKeyImpl.generateWrappingKey(schemeMetadata)

        val alias = UUID.randomUUID().toString()
        var saveCount = 0
        var findCount = 0
        val countingWrappingStore = object : TestWrappingKeyStore(mock()) {
            override fun saveWrappingKey(alias: String, key: WrappingKeyInfo) {
                saveCount++
                return super.saveWrappingKey(alias, key)
            }

            override fun findWrappingKey(alias: String): WrappingKeyInfo? {
                findCount++
                return super.findWrappingKey(alias)
            }
        }
        val myCryptoService = SoftCryptoService(
            countingWrappingStore, schemeMetadata,
            rootWrappingKey, makeWrappingKeyCache(), makePrivateKeyCache()
        )
        myCryptoService.createWrappingKey(alias, true, mapOf())
        assertThat(findCount).isEqualTo(1) // we do a find to check for conflicts
        myCryptoService.getWrappingKey(alias)
        assertThat(saveCount).isEqualTo(1)
        assertThat(findCount).isEqualTo(1) // but we should not do another find
    }

    @Test
    fun `wrappingKeyExists should return true whenever key exist in cache or store and false otherwise`() {
        val storeAlias = UUID.randomUUID().toString()
        val cacheAlias = UUID.randomUUID().toString()
        val unknownAlias = UUID.randomUUID().toString()
        val wrappingKeyCache = makeWrappingKeyCache()
        val schemaMetadata = CipherSchemeMetadataImpl()
        val knownWrappingKey = WrappingKeyImpl.generateWrappingKey(schemeMetadata)
        val wrappingKeyStore = TestWrappingKeyStore(mock())
        val myCryptoService = SoftCryptoService(
            wrappingKeyStore,
            schemaMetadata,
            rootWrappingKey,
            wrappingKeyCache,
            makePrivateKeyCache()
        )
        assertFalse(myCryptoService.wrappingKeyExists(storeAlias))
        assertFalse(myCryptoService.wrappingKeyExists(cacheAlias))
        assertFalse(myCryptoService.wrappingKeyExists(unknownAlias))
        wrappingKeyCache.put(cacheAlias, knownWrappingKey)
        assertFalse(myCryptoService.wrappingKeyExists(storeAlias))
        assertTrue(myCryptoService.wrappingKeyExists(cacheAlias))
        assertFalse(myCryptoService.wrappingKeyExists(unknownAlias))
        wrappingKeyStore.saveWrappingKey(
            storeAlias,
            WrappingKeyInfo(1, "t", byteArrayOf())
        )
        assertTrue(myCryptoService.wrappingKeyExists(storeAlias))
        assertFalse(myCryptoService.wrappingKeyExists(unknownAlias))
        assertTrue(myCryptoService.wrappingKeyExists(cacheAlias))
    }

}