package net.corda.crypto.impl.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import java.util.UUID
import net.corda.crypto.core.Encryptor
import net.corda.crypto.core.aes.AesEncryptor
import net.corda.crypto.core.aes.AesKey
import net.corda.crypto.core.aes.KeyCredentials
import net.corda.libs.configuration.SmartConfig
import net.corda.libs.configuration.SmartConfigFactory
import net.corda.schema.configuration.BootConfig.BOOT_CRYPTO
import net.corda.schema.configuration.ConfigKeys.CRYPTO_CONFIG
import net.corda.v5.crypto.failures.CryptoException

/*
{
  "rootKey": {
        "salt": "<plain-text-value>",
        "passphrase": {
            "configSecret": {
                "encryptedSecret": "<encrypted-value>"
            }
        }
    },
    "softPersistence": {
        "expireAfterAccessMins": 240,
        "maximumSize": 1000,
        "maxAttempts": 1,
        "attemptTimeoutMills": 20000,
        "salt": "<plain-text-value>",
        "passphrase": {
            "configSecret": {
                "encryptedSecret": "<encrypted-value>"
            }
        }
    },
    "signingPersistence": {
        "keysExpireAfterAccessMins": 90,
        "keyNumberLimit": 20,
        "vnodesExpireAfterAccessMins": 120,
        "vnodeNumberLimit": 100,
        "connectionsExpireAfterAccessMins": 15,
        "connectionNumberLimit": 2
    },
    "hsmPersistence": {
        "expireAfterAccessMins": 240,
        "maximumSize": 1000,
        "downstreamMaxAttempts": 3
    },
    "bus": {
        "processors": {
            "ops": {
                "maxAttempts": 3,
                "waitBetweenMills": [200]
            },
            "flow": {
                "maxAttempts": 3,
                "waitBetweenMills": [200]
            },
            "config": {
                "maxAttempts": 3,
                "waitBetweenMills": [200]
            },
            "registration": {
                "maxAttempts": 3,
                "waitBetweenMills": [200]
            }
        }
    }
}
 */

private const val ROOT_KEY_PASSPHRASE = "rootKey.passphrase"
private const val ROOT_KEY_SALT = "rootKey.salt"
private const val SOFT_PERSISTENCE_OBJ = "softPersistence"
private const val SIGNING_PERSISTENCE_OBJ = "signingPersistence"
private const val HSM_PERSISTENCE_OBJ = "hsmPersistence"
private const val BUS_PROCESSORS_OBJ = "bus.processors"
private const val OPS_BUS_PROCESSOR_OBJ = "ops"
private const val FLOW_BUS_PROCESSOR_OBJ = "flow"
private const val HSM_CONFIG_BUS_PROCESSOR_OBJ = "config"
private const val HSM_REGISTRATION_BUS_PROCESSOR_OBJ = "registration"

fun createDefaultCryptoConfig(smartFactoryKey: KeyCredentials): SmartConfig =
    createDefaultCryptoConfig(
        smartFactoryKey = smartFactoryKey,
        cryptoRootKey = KeyCredentials(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString()
        ),
        softKey = KeyCredentials(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString()
        )
    )

fun createDefaultCryptoConfig(
    smartFactoryKey: KeyCredentials,
    cryptoRootKey: KeyCredentials,
    softKey: KeyCredentials
): SmartConfig =
    SmartConfigFactory.create(
        ConfigFactory.parseString(
            """
            ${SmartConfigFactory.SECRET_PASSPHRASE_KEY}=${smartFactoryKey.passphrase}
            ${SmartConfigFactory.SECRET_SALT_KEY}=${smartFactoryKey.salt}
        """.trimIndent()
        )
    ).createDefaultCryptoConfig(cryptoRootKey, softKey)

fun SmartConfig.addDefaultBootCryptoConfig(
    fallbackCryptoRootKey: KeyCredentials,
    fallbackSoftKey: KeyCredentials
): SmartConfig {
    val cryptoLibrary = if(hasPath(BOOT_CRYPTO)) {
        getConfig(BOOT_CRYPTO)
    } else {
        null
    }
    val cryptoRootKeyPassphrase = if(cryptoLibrary?.hasPath(ROOT_KEY_PASSPHRASE) == true) {
        cryptoLibrary.getString(ROOT_KEY_PASSPHRASE)
    } else {
        fallbackCryptoRootKey.passphrase
    }
    val cryptoRootKeySalt = if(cryptoLibrary?.hasPath(ROOT_KEY_SALT) == true) {
        cryptoLibrary.getString(ROOT_KEY_SALT)
    } else {
        fallbackCryptoRootKey.salt
    }
    val softPersistenceConfig = if(cryptoLibrary?.hasPath(SOFT_PERSISTENCE_OBJ) == true) {
        cryptoLibrary.getConfig(SOFT_PERSISTENCE_OBJ)
    } else {
        null
    }
    val softKeyPassphrase = if(softPersistenceConfig?.hasPath(CryptoSoftPersistenceConfig::passphrase.name) == true) {
        softPersistenceConfig.getString(CryptoSoftPersistenceConfig::passphrase.name)
    } else {
        fallbackSoftKey.passphrase
    }
    val softKeySoft = if(softPersistenceConfig?.hasPath(CryptoSoftPersistenceConfig::salt.name) == true) {
        softPersistenceConfig.getString(CryptoSoftPersistenceConfig::salt.name)
    } else {
        fallbackSoftKey.salt
    }
    val cryptoRootKey = KeyCredentials(cryptoRootKeyPassphrase, cryptoRootKeySalt)
    val softKey = KeyCredentials(softKeyPassphrase, softKeySoft)
    return withFallback(
        withValue(
            BOOT_CRYPTO,
            ConfigValueFactory.fromMap(
                factory.createDefaultCryptoConfig(cryptoRootKey, softKey).root().unwrapped()
            )
        )
    )
}

fun SmartConfigFactory.createDefaultCryptoConfig(
    cryptoRootKey: KeyCredentials,
    softKey: KeyCredentials
): SmartConfig = try {
    this.create(
        ConfigFactory.empty()
            .withValue(ROOT_KEY_SALT, ConfigValueFactory.fromAnyRef(cryptoRootKey.salt))
            .withValue(
                ROOT_KEY_PASSPHRASE, ConfigValueFactory.fromMap(
                    makeSecret(cryptoRootKey.passphrase).root().unwrapped()
                )
            )
            .withValue(
                SOFT_PERSISTENCE_OBJ, ConfigValueFactory.fromMap(
                    mapOf(
                        CryptoSoftPersistenceConfig::expireAfterAccessMins.name to "240",
                        CryptoSoftPersistenceConfig::maximumSize.name to "1000",
                        CryptoSoftPersistenceConfig::salt.name to softKey.salt,
                        CryptoSoftPersistenceConfig::passphrase.name to ConfigValueFactory.fromMap(
                            makeSecret(softKey.passphrase).root().unwrapped()
                        ),
                        CryptoSoftPersistenceConfig::maxAttempts.name to "1",
                        CryptoSoftPersistenceConfig::attemptTimeoutMills.name to "20000"
                    )
                )
            )
            .withValue(
                SIGNING_PERSISTENCE_OBJ, ConfigValueFactory.fromMap(
                    mapOf(
                        CryptoSigningPersistenceConfig::keysExpireAfterAccessMins.name to "90",
                        CryptoSigningPersistenceConfig::keyNumberLimit.name to "20",
                        CryptoSigningPersistenceConfig::vnodesExpireAfterAccessMins.name to "120",
                        CryptoSigningPersistenceConfig::vnodeNumberLimit.name to "100",
                        CryptoSigningPersistenceConfig::connectionsExpireAfterAccessMins.name to "15",
                        CryptoSigningPersistenceConfig::connectionNumberLimit.name to "2"
                    )
                )
            ).withValue(
                HSM_PERSISTENCE_OBJ, ConfigValueFactory.fromMap(
                    mapOf(
                        CryptoHSMPersistenceConfig::expireAfterAccessMins.name to "240",
                        CryptoHSMPersistenceConfig::maximumSize.name to "1000",
                        CryptoHSMPersistenceConfig::downstreamMaxAttempts.name to "3",
                    )
                )
            ).withValue(
                BUS_PROCESSORS_OBJ, ConfigValueFactory.fromMap(
                    mapOf(
                        OPS_BUS_PROCESSOR_OBJ to mapOf(
                            BusProcessorConfig::maxAttempts.name to "3",
                            BusProcessorConfig::waitBetweenMills.name to ConfigValueFactory.fromIterable(listOf(200)),
                        ),
                        FLOW_BUS_PROCESSOR_OBJ to mapOf(
                            BusProcessorConfig::maxAttempts.name to "3",
                            BusProcessorConfig::waitBetweenMills.name to ConfigValueFactory.fromIterable(listOf(200)),
                        ),
                        HSM_CONFIG_BUS_PROCESSOR_OBJ to mapOf(
                            BusProcessorConfig::maxAttempts.name to "3",
                            BusProcessorConfig::waitBetweenMills.name to ConfigValueFactory.fromIterable(listOf(200)),
                        ),
                        HSM_REGISTRATION_BUS_PROCESSOR_OBJ to mapOf(
                            BusProcessorConfig::maxAttempts.name to "3",
                            BusProcessorConfig::waitBetweenMills.name to ConfigValueFactory.fromIterable(listOf(200)),
                        )
                    ),
                )
            )
    )
} catch (e: Throwable) {
    throw CryptoException("Failed to create default crypto config", e)
}

fun Map<String, SmartConfig>.toCryptoConfig(): SmartConfig =
    this[CRYPTO_CONFIG] ?: throw IllegalStateException(
        "Could not generate a crypto configuration due to missing key: $CRYPTO_CONFIG"
    )

fun SmartConfig.rootEncryptor(): Encryptor =
    try {
        val key = AesKey.derive(
            passphrase = getString(ROOT_KEY_PASSPHRASE),
            salt = getString(ROOT_KEY_SALT)
        )
        AesEncryptor(key)
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get Encryptor.", e)
    }

fun SmartConfig.softPersistence(): CryptoSoftPersistenceConfig =
    try {
        CryptoSoftPersistenceConfig(getConfig(SOFT_PERSISTENCE_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get CryptoSoftPersistenceConfig.", e)
    }

fun SmartConfig.signingPersistence(): CryptoSigningPersistenceConfig =
    try {
        CryptoSigningPersistenceConfig(getConfig(SIGNING_PERSISTENCE_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get CryptoSigningPersistenceConfig.", e)
    }

fun SmartConfig.hsmPersistence(): CryptoHSMPersistenceConfig =
    try {
        CryptoHSMPersistenceConfig(getConfig(HSM_PERSISTENCE_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get CryptoHSMPersistenceConfig.", e)
    }

fun SmartConfig.opsBusProcessor(): BusProcessorConfig =
    try {
        BusProcessorConfig(getConfig(BUS_PROCESSORS_OBJ).getConfig(OPS_BUS_PROCESSOR_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get BusProcessorConfig for ops operations.", e)
    }

fun SmartConfig.flowBusProcessor(): BusProcessorConfig =
    try {
        BusProcessorConfig(getConfig(BUS_PROCESSORS_OBJ).getConfig(FLOW_BUS_PROCESSOR_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get BusProcessorConfig for flow ops operations.", e)
    }

fun SmartConfig.hsmConfigBusProcessor(): BusProcessorConfig =
    try {
        BusProcessorConfig(getConfig(BUS_PROCESSORS_OBJ).getConfig(HSM_CONFIG_BUS_PROCESSOR_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get BusProcessorConfig for hsm config operations.", e)
    }

fun SmartConfig.hsmRegistrationBusProcessor(): BusProcessorConfig =
    try {
        BusProcessorConfig(getConfig(BUS_PROCESSORS_OBJ).getConfig(HSM_REGISTRATION_BUS_PROCESSOR_OBJ))
    } catch (e: Throwable) {
        throw IllegalStateException("Failed to get BusProcessorConfig for hsm registration operations.", e)
    }
