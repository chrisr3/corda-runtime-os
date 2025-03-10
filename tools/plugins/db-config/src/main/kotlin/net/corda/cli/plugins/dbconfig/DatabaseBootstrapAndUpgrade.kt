package net.corda.cli.plugins.dbconfig

import net.corda.cli.api.CordaCliPlugin
import org.pf4j.Extension
import org.pf4j.Plugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine

class DatabaseBootstrapAndUpgrade : Plugin() {

    companion object {
        val classLoader: ClassLoader = this::class.java.classLoader
        private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    }

    override fun start() {
        logger.debug("Bootstrap plugin started.")
    }

    override fun stop() {
        logger.debug("Bootstrap plugin stopped.")
    }

    @Extension
    @CommandLine.Command(name = "database", subcommands = [Spec::class], description = ["Does Database bootstrapping and upgrade"])
    class PluginEntryPoint : CordaCliPlugin
}
