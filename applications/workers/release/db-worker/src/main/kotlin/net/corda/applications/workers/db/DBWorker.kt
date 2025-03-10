package net.corda.applications.workers.db

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
import net.corda.libs.configuration.secret.SecretsServiceFactoryResolver
import net.corda.libs.configuration.validation.ConfigurationValidatorFactory
import net.corda.libs.platform.PlatformInfoProvider
import net.corda.osgi.api.Application
import net.corda.osgi.api.Shutdown
import net.corda.processors.db.DBProcessor
import net.corda.processors.token.cache.TokenCacheProcessor
import net.corda.schema.configuration.BootConfig.BOOT_DB
import net.corda.tracing.configureTracing
import net.corda.tracing.shutdownTracing
import net.corda.web.api.WebServer
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.LoggerFactory
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

/** The worker for interacting with the database. */
@Suppress("Unused", "LongParameterList")
@Component(service = [Application::class])
class DBWorker @Activate constructor(
    @Reference(service = DBProcessor::class)
    private val processor: DBProcessor,
    @Reference(service = TokenCacheProcessor::class)
    private val tokenCacheProcessor: TokenCacheProcessor,
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

    /** Parses the arguments, then initialises and starts the [processor]. */
    override fun startup(args: Array<String>) {
        logger.info("DB worker starting.")
        logger.loggerStartupInfo(platformInfoProvider)

        applicationBanner.show("DB Worker", platformInfoProvider)

        JavaSerialisationFilter.install()


        val params = getParams(args, DBWorkerParams())

        webServer.setupWebserver(params.defaultParams)
        if (printHelpOrVersion(params.defaultParams, DBWorker::class.java, shutDownService)) return
        setupMonitor(workerMonitor, params.defaultParams, this.javaClass.simpleName)

        configureTracing("DB Worker", params.defaultParams.zipkinTraceUrl, params.defaultParams.traceSamplesPerSecond)

        val databaseConfig = PathAndConfig(BOOT_DB, params.databaseParams)
        val config = getBootstrapConfig(
            secretsServiceFactoryResolver,
            params.defaultParams,
            configurationValidatorFactory.createConfigValidator(),
            listOf(databaseConfig)
        )

        processor.start(config)
        tokenCacheProcessor.start(config)
    }

    override fun shutdown() {
        logger.info("DB worker stopping.")
        processor.stop()
        webServer.stop()
        tokenCacheProcessor.stop()
        shutdownTracing()
    }
}

/** Additional parameters for the DB worker are added here. */
private class DBWorkerParams {
    @Mixin
    var defaultParams = DefaultWorkerParams()

    @Option(names = ["-d", "--$BOOT_DB"], description = ["Database parameters for the worker."])
    var databaseParams = emptyMap<String, String>()
}
