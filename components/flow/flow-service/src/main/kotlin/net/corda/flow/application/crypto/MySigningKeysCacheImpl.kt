package net.corda.flow.application.crypto

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.cache.caffeine.CacheFactoryImpl
import net.corda.sandboxgroupcontext.CurrentSandboxGroupContext
import net.corda.sandboxgroupcontext.SandboxGroupType
import net.corda.sandboxgroupcontext.SandboxedCache
import net.corda.sandboxgroupcontext.SandboxedCache.CacheKey
import net.corda.sandboxgroupcontext.VirtualNodeContext
import net.corda.sandboxgroupcontext.service.CacheEviction
import net.corda.utilities.debug
import net.corda.virtualnode.HoldingIdentity
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Deactivate
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.PublicKey

@Component(service = [MySigningKeysCache::class])
class MySigningKeysCacheImpl @Activate constructor(
    @Reference(service = CurrentSandboxGroupContext::class)
    private val currentSandboxGroupContext: CurrentSandboxGroupContext,
    @Reference(service = CacheEviction::class)
    private val cacheEviction: CacheEviction
) : MySigningKeysCache, SandboxedCache {

    private data class CacheValue(val publicKey: PublicKey?)

    // TODO Access configuration to setup the cache
    private companion object {
        private val log: Logger = LoggerFactory.getLogger(MySigningKeysCacheImpl::class.java)
        private const val MY_SIGNING_KEYS_CACHE_MAX_SIZE_PROPERTY_NAME = "net.corda.flow.application.crypto.cache.maximumSize"
    }

    private val maximumSize = java.lang.Long.getLong(MY_SIGNING_KEYS_CACHE_MAX_SIZE_PROPERTY_NAME, 10000)

    private val cache: Cache<CacheKey<PublicKey>, CacheValue> = CacheFactoryImpl().build(
        "My-Signing-Key-Cache",
        Caffeine.newBuilder().maximumSize(maximumSize)
    )

    init {
        if (!cacheEviction.addEvictionListener(SandboxGroupType.FLOW, ::onEviction)) {
            log.error("FAILED TO ADD EVICTION LISTENER")
        }
    }

    @Suppress("unused")
    @Deactivate
    fun shutdown() {
        if (!cacheEviction.removeEvictionListener(SandboxGroupType.FLOW, ::onEviction)) {
            log.error("FAILED TO REMOVE EVICTION LISTENER")
        }
    }

    private fun onEviction(vnc: VirtualNodeContext) {
        log.debug {
            "Evicting cached items from ${cache::class.java} with holding identity: ${vnc.holdingIdentity} and sandbox type: " +
                    SandboxGroupType.FLOW
        }
        remove(vnc.holdingIdentity)
    }

    override fun get(keys: Set<PublicKey>): Map<PublicKey, PublicKey?> {
        return if (keys.isNotEmpty()) {
            val virtualNodeContext = currentSandboxGroupContext.get().virtualNodeContext
            cache.getAllPresent(keys.map { CacheKey(virtualNodeContext, it) })
                .map { (key, value) -> key.key to value.publicKey }
                .toMap()
        } else {
            emptyMap()
        }
    }

    override fun putAll(keys: Map<out PublicKey, PublicKey?>) {
        if (keys.isNotEmpty()) {
            val virtualNodeContext = currentSandboxGroupContext.get().virtualNodeContext
            cache.putAll(keys.map { (key, value) -> CacheKey(virtualNodeContext, key) to CacheValue(value) }.toMap())
        }
    }

    override fun remove(holdingIdentity: HoldingIdentity) {
        cache.invalidateAll(cache.asMap().keys.filter { it.holdingIdentity == holdingIdentity })
        cache.cleanUp()
    }
}