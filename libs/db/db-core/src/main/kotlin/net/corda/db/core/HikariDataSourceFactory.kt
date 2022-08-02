package net.corda.db.core

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.system.exitProcess

class HikariDataSourceFactory(
    private val hikariDataSourceFactory: (c: HikariConfig) -> CloseableDataSource = { c ->
        val uuid = getNewUuid()
        println("New HikariDataSource - $uuid\n${Throwable().stackTraceToString()}")
        DataSourceWrapper(HikariDataSource(c), uuid)
    }
) : DataSourceFactory {


    /**
     * [HikariDataSource] wrapper that makes it [CloseableDataSource]
     */
    private class DataSourceWrapper(private val delegate: HikariDataSource, private val uuid: String):
        CloseableDataSource, DataSource by delegate {

        override fun getConnection(username: String?, password: String?): Connection {
            val uuid = getNewUuid()
            println("New Hikari connection - $uuid")

            val c = delegate.getConnection(username, password)
            return object : Connection by c {
                override fun close() {
                    println("Close Hikari connection - $uuid")
                    c.close()
                }
            }
        }

        override fun getConnection(): Connection {
            val uuid = getNewUuid()
            println("New Hikari connection - $uuid")
            connectionMap[uuid] = true
            val stack = Throwable().stackTraceToString()
            connectionSourceMap.compute(stack) { _, u -> u?.inc() ?: 1 }

            val c = delegate.connection
            return object : Connection by c {
                override fun close() {
                    println("Close Hikari connection - $uuid")
                    connectionMap[uuid] = false
                    c.close()

                    println("total connections: ${connectionMap.count()}")
                    val count = connectionMap.filter { it.value }.count()
                    println("connections open: $count")

                    if (connectionMap.count() > 1000) {
                        synchronized(connectionSourceMap)
                        {
                            connectionSourceMap.asSequence().sortedBy { it.value }.forEach {
                                println("${it.value} connections: \n${it.key.prependIndent("   ")}")
                            }
                            exitProcess(1)
                        }                    }
                }
            }
        }

        override fun close() {
            println("DataSourceWrapper.close() - $uuid")
            delegate.close()
        }
    }

    override fun create(
        driverClass: String,
        jdbcUrl: String,
        username: String,
        password: String,
        isAutoCommit: Boolean,
        maximumPoolSize: Int
    ): CloseableDataSource {
        val conf = HikariConfig()
        conf.driverClassName = driverClass
        conf.jdbcUrl = jdbcUrl
        conf.username = username
        conf.password = password
        conf.isAutoCommit = isAutoCommit
        conf.maximumPoolSize = maximumPoolSize
        return hikariDataSourceFactory(conf)
    }

    companion object {
        private val connectionMap: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()
        private val connectionSourceMap: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    }
}

private fun getNewUuid() = UUID.randomUUID().toString()
