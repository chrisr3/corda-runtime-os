package net.corda.applications.workers.crypto

import net.corda.applications.workers.workercommon.ApplicationBanner
import net.corda.applications.workers.workercommon.DefaultWorkerParams
import net.corda.applications.workers.workercommon.JavaSerialisationFilter
import net.corda.applications.workers.workercommon.PathAndConfig
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.getBootstrapConfig
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.getParams
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.loggerStartupInfo
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.printHelpOrVersion
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.setupMonitor
import net.corda.applications.workers.workercommon.WorkerHelpers.Companion.setupWebserver
import net.corda.applications.workers.workercommon.WorkerMonitor
import net.corda.crypto.config.impl.createCryptoBootstrapParamsMap
import net.corda.libs.configuration.SmartConfig
import net.corda.libs.configuration.secret.SecretsServiceFactoryResolver
import net.corda.libs.configuration.validation.ConfigurationValidatorFactory
import net.corda.libs.platform.PlatformInfoProvider
import net.corda.osgi.api.Application
import net.corda.osgi.api.Shutdown
import net.corda.processors.crypto.CryptoProcessor
import net.corda.schema.configuration.BootConfig.BOOT_CRYPTO
import net.corda.schema.configuration.BootConfig.BOOT_DB
import net.corda.tracing.configureTracing
import net.corda.tracing.shutdownTracing
import net.corda.web.api.WebServer
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Mixin

/** The worker for interacting with the key material. */
@Suppress("Unused", "LongParameterList")
@Component(service = [Application::class])
class CryptoWorker @Activate constructor(
    @Reference(service = CryptoProcessor::class)
    private val processor: CryptoProcessor,
    @Reference(service = Shutdown::class)
    private val shutDownService: Shutdown,
    @Reference(service = WorkerMonitor::class)
    private val workerMonitor: WorkerMonitor,
    @Reference(service = WebServer::class)
    private val webServer: WebServer,
    @Reference(service = ConfigurationValidatorFactory::class)
    private val configurationValidatorFactory: ConfigurationValidatorFactory,
    @Reference(service = PlatformInfoProvider::class)
    val platformInfoProvider: PlatformInfoProvider,
    @Reference(service = ApplicationBanner::class)
    val applicationBanner: ApplicationBanner,
    @Reference(service = SecretsServiceFactoryResolver::class)
    val secretsServiceFactoryResolver: SecretsServiceFactoryResolver,
) : Application {

    private companion object {
        private val logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun startup(args: Array<String>) {
        logger.info("Crypto worker starting.")
        logger.loggerStartupInfo(platformInfoProvider)

        applicationBanner.show("Crypto Worker", platformInfoProvider)

        JavaSerialisationFilter.install()
        val params = getParams(args, CryptoWorkerParams())
        webServer.setupWebserver(params.defaultParams)
        if (printHelpOrVersion(params.defaultParams, CryptoWorker::class.java, shutDownService)) {
            return
        }
        if (params.hsmId.isBlank()) {
            throw IllegalStateException("Please specify which HSM the worker must handle, like --hsm-id SOFT")
        }
        setupMonitor(workerMonitor, params.defaultParams, this.javaClass.simpleName)

        configureTracing("Crypto Worker", params.defaultParams.zipkinTraceUrl, params.defaultParams.traceSamplesPerSecond)

        processor.start(
            buildBoostrapConfig(params, configurationValidatorFactory)
        )
    }

    override fun shutdown() {
        logger.info("Crypto worker stopping.")
        processor.stop()
        webServer.stop()
        shutdownTracing()
    }

    private fun buildBoostrapConfig(
        params: CryptoWorkerParams,
        configurationValidatorFactory: ConfigurationValidatorFactory
    ): SmartConfig = getBootstrapConfig(
        secretsServiceFactoryResolver,
        params.defaultParams,
        configurationValidatorFactory.createConfigValidator(),
        listOf(
            PathAndConfig(BOOT_DB, params.databaseParams),
            PathAndConfig(BOOT_CRYPTO, createCryptoBootstrapParamsMap(params.hsmId))
        )
    )
}

class CryptoWorkerParams {
    @Mixin
    var defaultParams = DefaultWorkerParams()

    @CommandLine.Option(names = ["-d", "--$BOOT_DB"], description = ["Database parameters for the worker."])
    var databaseParams = emptyMap<String, String>()

    // TODO - delete me as part of removing multiple HSM support, CORE-10050
    @CommandLine.Option(names = ["--hsm-id"], description = ["HSM ID which is handled by this worker instance."])
    var hsmId = ""
}