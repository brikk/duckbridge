package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/** Independent protocol/client observations; never synthesize affected counts from a second SELECT. */
class TestQuackTriggeredCountContracts {
    @Test
    fun testJdbcCursorAndNativeWrapperExposeTheActualCountContract() {
        TestingQuackServer().use { server ->
            lifecycleConnection(server).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE target(id INTEGER)")
                    statement.execute("CREATE TABLE audit(id INTEGER)")
                    statement.execute("CREATE TRIGGER ai AFTER INSERT ON target FOR EACH ROW INSERT INTO audit VALUES(NEW.id)")
                    val result = statement.execute("INSERT INTO target VALUES(1),(2)")
                    println("TRIGGER JDBC hasResult=$result count=${statement.updateCount}")
                    assertThat(result).describedAs("Triggered DML must be an update count, not an ordinary SELECT result").isFalse()
                    assertThat(statement.updateCount).isEqualTo(2)
                }
                val cursor = connection.session().cursor("INSERT INTO target VALUES(3),(4)")
                println("TRIGGER cursor names=${cursor.columnNames()} types=${cursor.columnTypes()} inline=${cursor.materializedRowCount()}")
                val chunks = cursor.drainAll()
                println("TRIGGER cursor chunks=" + chunks.map { chunk ->
                    (0 until chunk.rowCount()).map { chunk.columns().first().getObject(it) }
                })
                assertThat(chunks.flatMap { chunk -> (0 until chunk.rowCount()).map { chunk.columns().first().getObject(it) } })
                    .containsExactly(2L)
                cursor.close()
            }
            DriverManager.getConnection("jdbc:duckdb:").use { wrapper ->
                wrapper.createStatement().use { statement ->
                    statement.execute("INSTALL quack")
                    statement.execute("LOAD quack")
                    statement.execute("CREATE SECRET (TYPE quack,TOKEN '${server.token}')")
                    statement.execute("ATTACH 'quack:${server.host}:${server.mappedPort}' AS count_remote (disable_ssl true)")
                    statement.executeQuery("SELECT * FROM quack_query_by_name('count_remote','INSERT INTO target VALUES(5),(6)')").use { rows ->
                        assertThat(rows.next()).isTrue()
                        println("TRIGGER native-wrapper ${rows.metaData.getColumnName(1)}=${rows.getObject(1)}")
                        assertThat(rows.getLong(1)).isEqualTo(2)
                        assertThat(rows.next()).isFalse()
                    }
                }
            }
            lifecycleConnection(server).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT (SELECT count(*) FROM target),(SELECT count(*) FROM audit)").use { rows ->
                        check(rows.next())
                        println("TRIGGER final target=${rows.getLong(1)} audit=${rows.getLong(2)}")
                        assertThat(rows.getLong(1)).isEqualTo(6)
                        assertThat(rows.getLong(2)).isEqualTo(6)
                    }
                }
            }
        }
    }

    @Test
    fun testSelectCountAliasesAndReturningStayResultSets() {
        TestingQuackServer().use { server ->
            lifecycleConnection(server).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE classified(id INTEGER)")
                    statement.execute("CREATE TABLE classified_audit(id INTEGER)")
                    statement.execute("CREATE TRIGGER ai AFTER INSERT ON classified FOR EACH STATEMENT " +
                        "INSERT INTO classified_audit SELECT id FROM classified")
                }
                for (sql in listOf("SELECT count(*) FROM range(2)", "SELECT 2 AS Count", "SELECT 2 AS rows_affected",
                    "SELECT 2 AS \"count_star()\"", "/* INSERT misleading comment */ SELECT count(*) FROM range(2)",
                    "WITH q AS (SELECT 2 AS Count) SELECT Count FROM q")) {
                    assertSelect(connection, sql)
                }
                for (alias in listOf("Count", "rows_affected", "\"count_star()\"")) {
                    assertReturning(connection, "INSERT INTO classified VALUES(7) RETURNING id AS $alias")
                }
            }
        }
    }

    private fun assertSelect(connection: java.sql.Connection, sql: String) {
        connection.createStatement().use { statement ->
            assertThat(statement.execute(sql)).describedAs(sql).isTrue()
            assertThat(statement.updateCount).isEqualTo(-1)
            statement.resultSet.use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getLong(1)).isEqualTo(2)
                assertThat(rows.next()).isFalse()
            }
        }
        connection.prepareStatement(sql).use { statement ->
            assertThat(statement.execute()).describedAs("Prepared %s", sql).isTrue()
            assertThat(statement.updateCount).isEqualTo(-1)
            statement.resultSet.use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getLong(1)).isEqualTo(2)
                assertThat(rows.next()).isFalse()
            }
        }
    }

    @Test
    fun testPreparedBatchAndCteDmlUseRealCounts() {
        TestingQuackServer().use { server ->
            lifecycleConnection(server).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE batched(id INTEGER,note VARCHAR)")
                    statement.execute("CREATE TABLE batched_audit(id INTEGER)")
                    statement.execute("CREATE TRIGGER ai AFTER INSERT ON batched FOR EACH ROW INSERT INTO batched_audit VALUES(NEW.id)")
                    assertThat(statement.executeUpdate("/* SELECT disguise */ INSERT INTO batched VALUES(1,'RETURNING'),(2,'x')")).isEqualTo(2)
                    assertThat(statement.executeUpdate("WITH q AS (SELECT 3 id) INSERT INTO batched SELECT id,'x' FROM q")).isEqualTo(1)
                    statement.addBatch("INSERT INTO batched VALUES(4,'x')")
                    statement.addBatch("INSERT INTO batched VALUES(5,'x')")
                    assertThat(statement.executeBatch()).containsExactly(1, 1)
                }
                connection.prepareStatement("INSERT INTO batched VALUES(?,?)").use { statement ->
                    for (id in listOf(6, 7)) { statement.setInt(1, id); statement.setString(2, "RETURNING"); statement.addBatch() }
                    assertThat(statement.executeBatch()).containsExactly(1, 1)
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT (SELECT count(*) FROM batched),(SELECT count(*) FROM batched_audit)").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getLong(1)).isEqualTo(7)
                        assertThat(rows.getLong(2)).isEqualTo(7)
                    }
                }
            }
        }
    }

    private fun assertReturning(connection: java.sql.Connection, sql: String) {
        connection.createStatement().use { statement ->
            assertThat(statement.execute(sql)).describedAs(sql).isTrue()
            assertThat(statement.updateCount).isEqualTo(-1)
            statement.resultSet.use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isEqualTo(7)
                assertThat(rows.next()).isFalse()
            }
        }
        connection.prepareStatement(sql).use { statement ->
            assertThat(statement.execute()).describedAs("Prepared %s", sql).isTrue()
            assertThat(statement.updateCount).isEqualTo(-1)
            statement.resultSet.use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isEqualTo(7)
                assertThat(rows.next()).isFalse()
            }
        }
    }
}
