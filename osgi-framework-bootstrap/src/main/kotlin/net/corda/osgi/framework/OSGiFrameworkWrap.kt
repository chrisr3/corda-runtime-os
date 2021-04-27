package net.corda.osgi.framework

import net.corda.osgi.framework.OSGiFrameworkWrap.Companion.getFrameworkFrom
import net.corda.osgi.framework.api.ArgsService
import org.osgi.framework.Bundle
import org.osgi.framework.BundleException
import org.osgi.framework.Constants
import org.osgi.framework.FrameworkEvent
import org.osgi.framework.launch.Framework
import org.osgi.framework.launch.FrameworkFactory
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * `OSGiFrameworkWrap` provides an API to bootstrap an OSGI framework and OSGi bundles in the classpath.
 *
 * This classpath can be either an executable jar or a runtime classpath generated by the IDE.
 *
 * The OSGi bundles are embedded in the directory `bundles`, which is a child of the root classpath.
 *
 * The file `system_bundles` in the root of the classpath lists the paths to access the bundles to activate.
 *
 * The file `system_packages_extra` in the root of the classpath lists packages exposed from this classpath to the
 * bundles active in the OSGi framework.
 *
 * The classpath or executable jar has the following structure.
 * ```
 *      <root_of_classpath>
 *      +--- bundles
 *      |    +--- <bundle_1.jar>
 *      |    +--- <...>
 *      |    +--- <bundle_n.jar>
 *      +--- system_bundles
 *      \___ system_packages_extra
 * ```
 *
 * @param framework to bootstrap.
 * Get the framework with [getFrameworkFrom] if the framework and its factory are in this classpath.
 */
class OSGiFrameworkWrap(
    private val framework: Framework,
) : AutoCloseable {

    companion object {

        private val logger = LoggerFactory.getLogger(OSGiFrameworkWrap::class.java)

        /**
         * Map the bundle state number to a description of the state.
         * Used to log.
         */
        private val bundleStateMap = mapOf(
            // 0x00000020 = 0010.0000 binary
            Bundle.ACTIVE to "active",
            // 0x00000002 = 0000.0010 binary
            Bundle.INSTALLED to "installed",
            // 0x00000004 = 0000.0100 binary
            Bundle.RESOLVED to "resolved",
            // 0x00000008 = 0000.1000 binary
            Bundle.STARTING to "starting",
            // 0x00000010 = 0001.0000 binary
            Bundle.STOPPING to "stopping",
            // 0x00000001 = 0000.0001 binary
            Bundle.UNINSTALLED to "uninstalled"
        )

        /**
         * Extension used to identify `jar` files to [install].
         */
        private const val JAR_EXTENSION = ".jar"

        /**
         * Return a new configured [Framework] loaded from the classpath and having [frameworkFactoryFQN] as
         * Full Qualified Name of the [FrameworkFactory].
         * Configure the [Framework] to set the bundles' cache to [frameworkStorageDir] path.
         *
         * The [FrameworkFactory] must be in the classpath.
         *
         * @param frameworkFactoryFQN Full Qualified Name of the [FrameworkFactory] making the [Framework] to return.
         * @param frameworkStorageDir Path to the directory the [Framework] uses as bundles' cache.
         * @param systemPackagesExtra Packages specified in this property are added to
         * the `org.osgi.framework.system.packages` property.
         * This allows the configurator to only define the additional packages and leave the standard execution
         * environment packages to be defined by the framework.
         * See [OSGi Core Release 7 - 4.2.2 Launching Properties](http://docs.osgi.org/specification/osgi.core/7.0.0/framework.lifecycle.html#framework.lifecycle.launchingproperties)
         * See [getFrameworkPropertyFrom] to load properties from resources.
         *
         * @return A new configured [Framework] loaded from the classpath and having [frameworkFactoryFQN] as
         *         Full Qualified Name of the [FrameworkFactory].
         *
         * @throws ClassNotFoundException If the [FrameworkFactory] specified in [frameworkFactoryFQN]
         *                                isn't in the classpath.
         * @throws SecurityException If a [SecurityManager] is installed and the caller hasn't [RuntimePermission].
         */
        @Suppress("MaxLineLength")
        @Throws(
            ClassNotFoundException::class,
            SecurityException::class
        )
        fun getFrameworkFrom(
            frameworkFactoryFQN: String,
            frameworkStorageDir: Path,
            systemPackagesExtra: String = ""
        ): Framework {
            logger.debug("OSGi framework factory = $frameworkFactoryFQN.")
            val frameworkFactory = Class.forName(
                frameworkFactoryFQN,
                true,
                OSGiFrameworkWrap::class.java.classLoader
            ).getDeclaredConstructor().newInstance() as FrameworkFactory
            val configurationMap = mapOf(
                Constants.FRAMEWORK_STORAGE to frameworkStorageDir.toString(),
                Constants.FRAMEWORK_STORAGE_CLEAN to Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT,
                Constants.FRAMEWORK_SYSTEMCAPABILITIES_EXTRA to systemPackagesExtra
            )
            if (logger.isDebugEnabled) {
                configurationMap.forEach { (key, value) -> logger.debug("OSGi property $key = $value.") }
            }
            return frameworkFactory.newFramework(configurationMap)
        }

        /**
         * Return the [resource] as a comma separated list to be used as a property to configure the the OSGi framework.
         * Ignore anything in a line after `#`.
         *
         * @param resource in the classpath from where to read the list.
         * @return the list loaded from [resource] as a comma separated text value.
         * @throws IOException If the [resource] can't be accessed.
         */
        fun getFrameworkPropertyFrom(resource: String): String {
            val resourceUrl = OSGiFrameworkMain::class.java.classLoader.getResource(resource)
                ?: throw IOException("OSGi property resource $resource not found in this classpath/jar.")
            val propertyValueList = resourceUrl.openStream().bufferedReader().useLines { lines ->
                lines.map { line -> line.substringBefore('#') }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
            }
            return propertyValueList.joinToString(",")
        }

        /**
         * Return `true` if the [bundle] is an
         * OSGi [fragment](https://www.osgi.org/developer/white-papers/semantic-versioning/bundles-and-fragments/).
         * OSGi fragments are not subject to activation.
         *
         * @param bundle to check if it is fragment.
         *
         * @return Return `true` if the 'bundle' is an OSGi fragment.
         */
        internal fun isFragment(bundle: Bundle): Boolean {
            return bundle.headers[Constants.FRAGMENT_HOST] != null
        }

        /**
         * Return `true` if the [state] LSB is between [Bundle.UNINSTALLED] and [Bundle.STOPPING] excluded
         * because the bundle is startable if [Bundle.getState] is inside this range.
         *
         * Bundle states are expressed as a bit-mask though a bundle can only be in one state at any time,
         * the state in the lifecycle is represented in the LSB of the value returned by [Bundle.getState].
         * See OSGi Core Release 7 [4.4.2 Bundle State](https://docs.osgi.org/specification/osgi.core/7.0.0/framework.lifecycle.html)
         *
         * @param state of the bundle.
         *
         * @return `true` if the [state] LSB is between [Bundle.UNINSTALLED] and [Bundle.STOPPING] excluded.
         */
        private fun isStartable(state: Int): Boolean {
            // The bundle lifecycle state is represented by LSB.
            val status = state and 0xff
            return status > Bundle.UNINSTALLED && status < Bundle.STOPPING
        }

        /**
         * Return `true` if the [state] LSB is between [Bundle.STARTING] and [Bundle.ACTIVE] excluded
         * because the bundle is stoppable if [Bundle.getState] is in this range.
         *
         * Bundle states are expressed as a bit-mask though a bundle can only be in one state at any time,
         * the state in the lifecycle is represented in the LSB of the value returned by [Bundle.getState].
         * See OSGi Core Release 7 [4.4.2 Bundle State](https://docs.osgi.org/specification/osgi.core/7.0.0/framework.lifecycle.html)
         *
         * @param state of the bundle.
         *
         * @return `true` if the [state] LSB is between [Bundle.STARTING] and [Bundle.ACTIVE] excluded.
         */
        private fun isStoppable(state: Int): Boolean {
            // The bundle lifecycle state is represented by LSB.
            val status = state and 0xff
            return status > Bundle.STARTING && state <= Bundle.ACTIVE
        }

    } //~ companion object

    /**
     * Map of the bundle installed.
     *
     * @see [activate]
     * @see [install]
     */
    private val bundleMap = ConcurrentHashMap<Long, Bundle>()

    /**
     * Activate (start) the bundles installed with [install].
     * Call the `start` methods of the classes implementing `BundleActivator` in the activated bundle.
     *
     * Bundle activation is idempotent.
     *
     * @return this.
     *
     * @throws BundleException if any bundled installed fails to start.
     * The first bundle failing to start interrupts the activation of each bundle it should activated next.
     */
    @Throws(
        BundleException::class
    )
    fun activate(): OSGiFrameworkWrap {
        bundleMap.values.forEach { bundle: Bundle ->
            if (isFragment(bundle)) {
                logger.info(
                    "OSGi bundle ${bundle.location}" +
                            " ID = ${bundle.bundleId} ${bundle.symbolicName ?: "\b"}" +
                            " ${bundle.version} ${bundleStateMap[bundle.state]} fragment."
                )
            } else {
                bundle.start()
            }
        }
        return this
    }

    /**
     * Return a read only snapshot map of installed bundles where bundle identifiers are keys of the map.
     *
     * @return a read only snapshot map of installed bundles where bundle identifiers are keys of the map.
     */
    fun getBundleMap(): Map<Long, Bundle> {
        return bundleMap.toMap()
    }

    /**
     * Install the bundles represented by the [resource] in this classpath in the [Framework] wrapped by this object.
     * All installed bundles starts with the method [activate].
     *
     * @param resource represents the path in the classpath where bundles are described.
     * The resource can be:
     * * the bundle `.jar` file;
     * * the file describing where bundles are, for example the file `system_bundles` at the root of the classpath.
     *
     * Any [resource] not terminating with the `.jar` extension is considered a list of bundles.
     *
     * @return this.
     *
     * @throws BundleException If the bundle represented in the [resource] fails to install.
     * @throws IllegalStateException If the wrapped [Framework] is not active.
     * @throws IOException If the [resource] can't be read.
     * @throws SecurityException If the caller does not have the appropriate
     *         `AdminPermission[installed bundle,LIFECYCLE]`, and the Java Runtime Environment supports permissions.
     *
     * @see [installBundleJar]
     * @see [installBundleList]
     */
    @Throws(
        BundleException::class,
        IllegalStateException::class,
        IOException::class,
        SecurityException::class
    )
    fun install(resource: String): OSGiFrameworkWrap {
        val contextClassLoader = Thread.currentThread().contextClassLoader
        if (resource.endsWith(JAR_EXTENSION)) {
            installBundleJar(resource, contextClassLoader)
        } else {
            installBundleList(resource, contextClassLoader)
        }
        return this
    }

    /**
     * Install the bundle of the `.jar` file represented in the [resource].
     *
     * @param resource representing the bundle `.jar` file in the classpath.
     *                 The [resource] is read through [ClassLoader.getResourceAsStream].
     * @param classLoader used to read the [resource].
     *
     * @throws BundleException If the bundle represented in the [resource] fails to install.
     * @throws IllegalStateException If the wrapped [Framework] is not active.
     * @throws IOException If the [resource] can't be read.
     * @throws SecurityException If the caller does not have the appropriate
     *         `AdminPermission[installed bundle,LIFECYCLE]`, and the Java Runtime Environment supports permissions.
     *
     * @see [install]
     */
    @Throws(
        BundleException::class,
        IllegalStateException::class,
        IOException::class,
        SecurityException::class,
    )
    private fun installBundleJar(resource: String, classLoader: ClassLoader) {
        logger.debug("OSGi bundle $resource installing...")
        classLoader.getResourceAsStream(resource).use { inputStream ->
            if (inputStream != null) {
                val bundleContext = framework.bundleContext
                    ?: throw IllegalStateException("OSGi framework not active yet.")
                val bundle = bundleContext.installBundle(resource, inputStream)
                bundleMap[bundle.bundleId] = bundle
                logger.debug("OSGi bundle $resource installed.")
            } else {
                throw IOException("OSGi bundle at $resource not found")
            }
        }
    }

    /**
     * Install the bundles listed in the [resource] file.
     * Each line represents the path to the resource representing one bundle.
     * Line text after the `#` char is ignored.
     * The resources are read through [ClassLoader.getResourceAsStream].
     *
     * @param resource representing the file list of the path to the resources representing the bundles to install.
     *                 The [resource] is read through [ClassLoader.getResourceAsStream].
     * @param classLoader used to read the [resource].
     *
     * @throws BundleException If the bundle represented in the [resource] fails to install.
     * @throws IllegalStateException If the wrapped [Framework] is not active.
     * @throws IOException If the [resource] can't be read.
     * @throws SecurityException If the caller does not have the appropriate
     *         `AdminPermission[installed bundle,LIFECYCLE]`, and the Java Runtime Environment supports permissions.
     *
     * @see [install]
     */
    @Throws(
        BundleException::class,
        IllegalStateException::class,
        IOException::class,
        SecurityException::class
    )
    private fun installBundleList(resource: String, classLoader: ClassLoader) {
        classLoader.getResourceAsStream(resource)?.use { inputStream ->
            logger.info("OSGi bundle list at $resource loading...")
            inputStream.bufferedReader().useLines { lines ->
                lines.map { line -> line.substringBefore('#') }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
                    .forEach(::install)
            }
            logger.info("OSGi bundle list at $resource loaded.")
        } ?: throw IOException("OSGi bundle list at $resource not found")
    }

    /**
     * Register the [args] in the [ArgsService] OSGi service to expose [args] to the active bundles.
     *
     * @param args in the [ArgsService] OSGi service to expose [args] to the active bundles.
     *
     * @return this.
     *
     * @throws IllegalStateException if the wrapped [Framework] isn't active yet.
     */
    @Throws(
        IllegalStateException::class,
    )
    fun setArguments(args: Array<String>): OSGiFrameworkWrap {
        val bundleContext = framework.bundleContext ?: throw IllegalStateException("OSGi framework not active yet.")
        bundleContext.registerService(ArgsService::class.java, ArgsService { args }, Hashtable<String, Any>())
        return this
    }

    /**
     * Start the [Framework] wrapped by this [OSGiFrameworkWrap].
     * If the [Framework] can't start, the method logs a warning describing the actual state of the framework.
     * Start the framework multiple times is harmless, it just logs the warning.
     *
     * @return this.
     *
     * @throws BundleException If the wrapped [Framework] could not be started.
     * @throws IllegalStateException If the [Framework.getBundleContext] return an invalid object,
     * something should never happen for the OSGi system bundle.
     * @throws SecurityException If the caller does not have the appropriate AdminPermission[this,EXECUTE],
     * and the Java Runtime Environment supports permissions.
     *
     * See [Framework.start]
     */
    @Throws(
        BundleException::class,
        IllegalStateException::class,
        SecurityException::class
    )
    fun start(): OSGiFrameworkWrap {
        if (isStartable(framework.state)) {
            framework.start()
            framework.bundleContext.addBundleListener { bundleEvent ->
                val bundle = bundleEvent.bundle
                logger.info(
                    "OSGi bundle ${bundle.location}" +
                            " ID = ${bundle.bundleId} ${bundle.symbolicName ?: "\b"}" +
                            " ${bundle.version} ${bundleStateMap[bundle.state]}."
                )
            }
            logger.info("OSGi framework ${framework::class.java.canonicalName} ${framework.version} started.")
        } else {
            logger.warn(
                "OSGi framework ${framework::class.java.canonicalName} start attempted: state is " +
                        "${bundleStateMap[framework.state]}!"
            )
        }
        return this
    }

    /**
     * Stop the [Framework] wrapped by this [OSGiFrameworkWrap].
     * If the [Framework] can't stop, the method logs a warning describing the actual state of the framework.
     * Stop the framework multiple times is harmless, it just logs the warning.
     *
     * @return this.
     *
     * @throws BundleException  If stopping the wrapped [Framework] could not be initiated.
     * @throws SecurityException  If the caller does not have the appropriate AdminPermission[this,EXECUTE],
     * and the Java Runtime Environment supports permissions.
     *
     * @see [Framework.stop]
     */
    @Throws(
        BundleException::class,
        SecurityException::class
    )
    fun stop(): OSGiFrameworkWrap {
        if (isStoppable(framework.state)) {
            logger.debug("OSGi framework stop...")
            framework.stop()
        } else {
            logger.warn(
                "OSGi framework ${framework::class.java.canonicalName} stop attempted: state is " +
                        "${bundleStateMap[framework.state]}!"
            )
        }
        return this
    }

    /**
     * Wait until this Framework has completely stopped.
     *
     * This method will only wait if called when the wrapped [Framework] is in the [Bundle.STARTING], [Bundle.ACTIVE],
     * or [Bundle.STOPPING] states. Otherwise it will return immediately.
     *
     * @param timeout Maximum number of milliseconds to wait until the framework has completely stopped.
     * A value of zero will wait indefinitely.
     * @return A [FrameworkEvent] indicating the reason this method returned.
     * The following [FrameworkEvent] types may be returned:
     * * [FrameworkEvent.STOPPED] - The wrapped [Framework] has been stopped or never started.
     * * [FrameworkEvent.STOPPED_UPDATE] - The wrapped [Framework] has been updated which has shutdown
     *   and will restart now.
     * * [FrameworkEvent.STOPPED_SYSTEM_REFRESHED] - The wrapped [Framework] has been stopped because a refresh
     *   operation on the system bundle.
     * * [FrameworkEvent.ERROR] - The wrapped [Framework] encountered an error while shutting down or an error
     *   has occurred which forced the framework to shutdown.
     * * [FrameworkEvent.WAIT_TIMEDOUT] - This method has timed out and returned before this Framework has stopped.
     *
     * @throws InterruptedException If another thread interrupted the current thread before or while the current
     * thread was waiting for this Framework to completely stop.
     * @throws IllegalArgumentException If the value of timeout is negative.
     *
     * See [Framework.waitForStop]
     */
    @Throws(
        IllegalArgumentException::class,
        InterruptedException::class
    )
    fun waitForStop(timeout: Long): FrameworkEvent {
        framework.waitForStop(timeout).let { frameworkEvent ->
            when (frameworkEvent.type) {
                FrameworkEvent.ERROR -> {
                    logger.error(
                        "OSGi framework stop error: ${frameworkEvent.throwable.message}!", frameworkEvent.throwable
                    )
                }
                FrameworkEvent.STOPPED -> {
                    logger.info("OSGi framework ${framework::class.java.canonicalName} ${framework.version} stopped.")
                }
                FrameworkEvent.WAIT_TIMEDOUT -> {
                    logger.warn("OSGi framework ${framework::class.java.canonicalName} ${framework.version} time out!")
                }
                else -> {
                    logger.error("OSGi framework stop: unknown event type ${frameworkEvent.type}!")
                }
            }
            return frameworkEvent
        }
    }

    //: AutoCloseable

    /**
     * Call [stop], implemented to provide [OSGiFrameworkWrap] in `try-with-resources/use` block.
     */
    override fun close() {
        stop()
    }

}