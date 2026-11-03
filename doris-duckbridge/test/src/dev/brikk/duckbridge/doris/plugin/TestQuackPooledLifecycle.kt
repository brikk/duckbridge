package dev.brikk.duckbridge.doris.plugin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException

/** Same Hikari version/validation query as the pinned BE. One physical slot forces real reuse. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestQuackPooledLifecycle {
    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec("CREATE TABLE lifecycle_rollback(id INTEGER)")
        server.exec("CREATE TABLE lifecycle_early_close(id INTEGER)")
    }

    @AfterAll
    fun tearDown() { if (::server.isInitialized) server.close() }

    private fun pool(): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = server.connectionUrl()
        driverClassName = TestingQuackServer.DRIVER_CLASS
        password = server.token
        maximumPoolSize = 1
        minimumIdle = 1
        connectionTimeout = 5000
        validationTimeout = 2000
        connectionTestQuery = "SELECT 1"
    })

    private fun scalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next()) { "Expected one scalar row" }
            rows.getInt(1).also { check(!rows.next()) }
        }
    }

    @Test
    fun parserBinderAndExecutionErrorsDoNotPoisonNextAutocommitBorrower() {
        pool().use { pool ->
            for (sql in listOf("SELECT (", "SELECT absent_column", "SELECT CAST('bad' AS INTEGER)")) {
                pool.connection.use { connection ->
                    assertThat(connection.autoCommit).isTrue()
                    assertThatThrownBy { scalar(connection, sql) }.isInstanceOf(SQLException::class.java)
                }
                pool.connection.use { connection ->
                    assertThat(connection.autoCommit).isTrue()
                    assertThat(scalar(connection, "SELECT 42")).isEqualTo(42)
                }
            }
            assertThat(pool.hikariPoolMXBean.totalConnections).isEqualTo(1)
        }
    }

    @Test
    fun abortedExplicitTransactionIsRolledBackBeforeReuse() {
        pool().use { pool ->
            pool.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("INSERT INTO lifecycle_rollback VALUES (1)") }
                assertThatThrownBy { scalar(connection, "SELECT CAST('bad' AS INTEGER)") }.isInstanceOf(SQLException::class.java)
                // No explicit cleanup: this is the return-to-pool contract under test.
            }
            pool.connection.use { connection ->
                assertThat(connection.autoCommit).isTrue()
                assertThat(scalar(connection, "SELECT count(*) FROM lifecycle_rollback")).isZero()
                assertThat(scalar(connection, "SELECT 42")).isEqualTo(42)
            }
        }
    }

    @Test
    fun earlyCloseSupersessionAndCommitPreserveManualTransaction() {
        pool().use { pool ->
            pool.connection.use { connection ->
                exerciseManualEarlyClose(connection)
            }
            pool.connection.use { connection ->
                assertThat(connection.autoCommit).isTrue()
                assertThat(scalar(connection, "SELECT count(*) FROM lifecycle_early_close")).isEqualTo(3)
                assertThat(scalar(connection, "SELECT sum(id) FROM lifecycle_early_close")).isEqualTo(6)
            }
        }
    }

    private fun exerciseManualEarlyClose(connection: Connection) {
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            assertThat(statement.executeUpdate("INSERT INTO lifecycle_early_close VALUES(1)")).isEqualTo(1)
            statement.executeQuery("SELECT * FROM range(10000)").use { rows -> assertThat(rows.next()).isTrue() }
            assertThat(statement.executeUpdate("INSERT INTO lifecycle_early_close VALUES(2)")).isEqualTo(1)
            statement.executeQuery("SELECT * FROM range(10000)").use { rows ->
                assertThat(rows.next()).isTrue()
                connection.createStatement().use { next ->
                    assertThat(next.executeUpdate("INSERT INTO lifecycle_early_close VALUES(3)")).isEqualTo(1)
                }
            }
            statement.executeQuery("SELECT * FROM range(10000)").use { rows ->
                assertThat(rows.next()).isTrue()
                connection.commit()
            }
        }
    }

    @Test
    fun preparedErrorAndEarlyResultCloseLeaveTheNextBorrowerUsable() {
        pool().use { pool ->
            pool.connection.use { connection ->
                exercisePrepared(connection)
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM range(100000)").use { rows -> assertThat(rows.next()).isTrue() }
                }
            }
            pool.connection.use { connection -> assertThat(scalar(connection, "SELECT 42")).isEqualTo(42) }
        }
    }

    private fun exercisePrepared(connection: Connection) {
        connection.prepareStatement("SELECT CAST(? AS INTEGER)").use { statement ->
            for (value in listOf(1, 2)) {
                statement.setInt(1, value)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(value)
                }
            }
            statement.setString(1, "bad")
            assertThatThrownBy { statement.executeQuery().close() }.isInstanceOf(SQLException::class.java)
            statement.setInt(1, 3)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isEqualTo(3)
            }
        }
    }
}
