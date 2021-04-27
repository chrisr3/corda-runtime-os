package net.corda.osgi.framework

import org.slf4j.LoggerFactory
import java.nio.file.Files

class OSGiFrameworkMain {

    companion object {

        /**
         * Full qualified name of the OSGi framework factory should be part of the class path.
         */
        const val FRAMEWORK_FACTORY_FQN = "org.apache.felix.framework.FrameworkFactory"

        /**
         * Prefix of the temporary directory used as bundle cache.
         */
        const val FRAMEWORK_STORAGE_PREFIX = "osgi-cache"

        /**
         * Location of the list of bundles to install in the [OSGiFrameworkWrap] instance.
         * The location is relative to run time class path:
         * * `build/resources/main` in a gradle project;
         * * the root of the fat executable `.jar`.
         */
        const val SYSTEM_BUNDLES = "system_bundles"

        /**
         * Location of the file listing the extra system packages exposed from the JDK to the framework.
         * See [OSGi Core Release 7 - 4.2.2 Launching Properties](http://docs.osgi.org/specification/osgi.core/7.0.0/framework.lifecycle.html#framework.lifecycle.launchingproperties)
         * The location is relative to run time class path:
         * * `build/resources/main` in a gradle project;
         * * the root of the fat executable `.jar`.
         */
        @Suppress("MaxLineLength")
        const val SYSTEM_PACKAGES_EXTRA = "system_packages_extra"

        /**
         * Wait for stop of the OSGi framework, without timeout.
         */
        private const val NO_TIMEOUT = 0L

        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun main(args: Array<String>) {
            val logger = LoggerFactory.getLogger(OSGiFrameworkMain::class.java)
            try {
                val frameworkStorageDir = Files.createTempDirectory(FRAMEWORK_STORAGE_PREFIX)
                frameworkStorageDir.toFile().deleteOnExit()
                val osgiFrameworkWrap = OSGiFrameworkWrap(
                    OSGiFrameworkWrap.getFrameworkFrom(
                        FRAMEWORK_FACTORY_FQN,
                        frameworkStorageDir,
                        OSGiFrameworkWrap.getFrameworkPropertyFrom(SYSTEM_PACKAGES_EXTRA)
                    )
                )
                try {
                    Runtime.getRuntime().addShutdownHook(object : Thread() {
                        override fun run() {
                            osgiFrameworkWrap.stop()
                        }
                    })
                    osgiFrameworkWrap
                        .start()
                        .setArguments(args)
                        .install(SYSTEM_BUNDLES)
                        .activate()
                        .waitForStop(NO_TIMEOUT)
                } catch (e: Exception) {
                    logger.error("Error: ${e.message}!", e)
                } finally {
                    osgiFrameworkWrap.stop()
                }
            } catch (e: IllegalArgumentException) {
                logger.error("Error: ${e.message}!", e)
            } catch (e: UnsupportedOperationException) {
                logger.error("Error: ${e.message}!", e)
            } catch (e: SecurityException) {
                logger.error("Error: ${e.message}!", e)
            }
        }

    } //~ companion object

}