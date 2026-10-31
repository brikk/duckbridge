package dev.brikk.duckbridge.trino.plugin

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Real connector DML and engine triggers. Trigger DDL is deliberately native, not a claimed Trino API. */
class TestTriggeredWrites : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private lateinit var url: String

    override fun createQueryRunner(): QueryRunner {
        url = DuckBridgeQueryRunner.freshDatabaseUrl()
        server = TestingQuackServer()
        checkNotNull(server.installParityExtension())
        DriverManager.getConnection(url).use { execute(it, "CREATE SCHEMA triggers") }
        lifecycleConnection(server).use { execute(it, "CREATE SCHEMA triggers") }
        return DuckBridgeQueryRunner.create(url).also {
            it.createCatalog("remote_writes", "duckbridge", mapOf("connection-url" to server.connectionUrl(), "duckbridge.quack.token" to server.token))
        }
    }

    @AfterAll
    fun closeServer() { if (::server.isInitialized) server.close() }

    private fun execute(connection: Connection, sql: String): Int = connection.createStatement().use { it.executeUpdate(sql) }

    private fun withTargets(label: String, rowTriggers: Boolean = false, action: (String, Connection, String) -> Unit) {
        for (catalog in listOf("duckbridge", "remote_writes")) {
            val connection = if (catalog == "duckbridge") DriverManager.getConnection(url) else lifecycleConnection(server)
            connection.use {
                val name = "triggers.${label}_target"
                seed(it, name, rowTriggers)
                try { action(catalog, it, name) }
                finally {
                    if (!it.autoCommit) { it.rollback(); it.autoCommit = true }
                    for (table in listOf(name, "${name}_audit", "${name}_ordering")) execute(it, "DROP TABLE $table")
                }
            }
        }
    }

    private fun seed(connection: Connection, name: String, rowTriggers: Boolean) {
        execute(connection, "CREATE TABLE $name(id INTEGER PRIMARY KEY,v INTEGER)")
        execute(connection, "CREATE TABLE ${name}_audit(action VARCHAR,id INTEGER,old_v INTEGER,new_v INTEGER CHECK(new_v>=0))")
        execute(connection, "CREATE TABLE ${name}_ordering(seq INTEGER,n BIGINT)")
        if (rowTriggers) {
            execute(connection, "CREATE TRIGGER ri AFTER INSERT ON $name FOR EACH ROW " +
                "INSERT INTO ${name}_audit VALUES('i',NEW.id,NULL,NEW.v)")
            execute(connection, "CREATE TRIGGER rd AFTER DELETE ON $name FOR EACH ROW " +
                "INSERT INTO ${name}_audit VALUES('d',OLD.id,OLD.v,NULL)")
        } else {
            statementTriggers(connection, name)
        }
    }

    private fun statementTriggers(connection: Connection, name: String) {
        execute(connection, "CREATE TRIGGER bi BEFORE INSERT ON $name FOR EACH STATEMENT INSERT INTO ${name}_ordering SELECT 1,count(*) FROM $name")
        execute(connection, "CREATE TRIGGER ai AFTER INSERT ON $name REFERENCING NEW TABLE AS n FOR EACH STATEMENT " +
            "INSERT INTO ${name}_audit SELECT 'i',id,NULL,v FROM n")
        execute(connection, "CREATE TRIGGER ai_order AFTER INSERT ON $name FOR EACH STATEMENT " +
            "INSERT INTO ${name}_ordering SELECT 2,count(*) FROM ${name}_audit WHERE action='i'")
        execute(connection, "CREATE TRIGGER au AFTER UPDATE ON $name REFERENCING OLD TABLE AS o NEW TABLE AS n FOR EACH STATEMENT " +
            "INSERT INTO ${name}_audit SELECT 'u',n.id,o.v,n.v FROM o JOIN n ON o.id=n.id")
        execute(connection, "CREATE TRIGGER ad AFTER DELETE ON $name REFERENCING OLD TABLE AS o FOR EACH STATEMENT " +
            "INSERT INTO ${name}_audit SELECT 'd',id,v,NULL FROM o")
    }

    private fun changed(sql: String, count: Long) {
        assertThat(computeActual(sql).updateCount.orElseThrow()).describedAs(sql).isEqualTo(count)
    }

    @Test
    fun testStatementTriggerTransitionsCountsAndCreationOrder() = withTargets("statements") { catalog, _, name ->
        changed("INSERT INTO $catalog.$name VALUES(1,10),(2,20),(3,30)", 3)
        assertQuery("SELECT seq,n FROM $catalog.${name}_ordering ORDER BY seq", "VALUES(1,CAST(0 AS BIGINT)),(2,CAST(3 AS BIGINT))")
        changed("UPDATE $catalog.$name SET v=99 WHERE id<=2", 2)
        changed("DELETE FROM $catalog.$name WHERE id=3", 1)
        assertQuery("SELECT id,v FROM $catalog.$name ORDER BY id", "VALUES(1,99),(2,99)")
        assertQuery("SELECT action,id,old_v,new_v FROM $catalog.${name}_audit ORDER BY action,id",
            "VALUES('d',3,30,NULL),('i',1,NULL,10),('i',2,NULL,20),('i',3,NULL,30),('u',1,10,99),('u',2,20,99)")
    }

    @Test
    fun testRowTriggersFireOncePerRowThroughStagedConnectorWrites() = withTargets("rows", true) { catalog, _, name ->
        changed("INSERT INTO $catalog.$name VALUES(1,10),(2,20),(3,30)", 3)
        changed("DELETE FROM $catalog.$name WHERE id<=2", 2)
        assertQuery("SELECT action,id,old_v,new_v FROM $catalog.${name}_audit ORDER BY action,id",
            "VALUES('d',1,10,NULL),('d',2,20,NULL),('i',1,NULL,10),('i',2,NULL,20),('i',3,NULL,30)")
    }

    @Test
    fun testFailedTriggerDoesNotCommitPartialSourceOrAuditChanges() = withTargets("failure", true) { catalog, _, name ->
        assertThatThrownBy { computeActual("INSERT INTO $catalog.$name VALUES(1,10),(2,-1)") }.hasMessageContaining("CHECK constraint")
        assertQuery("SELECT count(*) FROM $catalog.$name", "VALUES CAST(0 AS BIGINT)")
        assertQuery("SELECT count(*) FROM $catalog.${name}_audit", "VALUES CAST(0 AS BIGINT)")
        changed("INSERT INTO $catalog.$name VALUES(3,50)", 1)
        assertQuery("SELECT id,v FROM $catalog.$name", "VALUES(3,50)")
        assertQuery("SELECT id,new_v FROM $catalog.${name}_audit", "VALUES(3,50)")
    }

    @Test
    fun testExplicitTransactionRollbackIncludesTriggerEffectsAndErrorInvalidation() = withTargets("rollback", true) { _, connection, name ->
        connection.autoCommit = false
        assertThat(execute(connection, "INSERT INTO $name VALUES(1,10)")).isEqualTo(1)
        assertThatThrownBy { execute(connection, "INSERT INTO $name VALUES(2,-1)") }.isInstanceOf(SQLException::class.java)
        assertThatThrownBy { execute(connection, "SELECT 42") }.isInstanceOf(SQLException::class.java).hasMessageContaining("aborted")
        connection.rollback()
        connection.autoCommit = true
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT (SELECT count(*) FROM $name),(SELECT count(*) FROM ${name}_audit)").use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getLong(1)).isZero()
                assertThat(rows.getLong(2)).isZero()
            }
        }
    }

    @Test
    fun testNativeReturningAndUnsupportedBeforeRowRemainExplicit() = withTargets("returning") { catalog, connection, name ->
        connection.createStatement().use { statement ->
            statement.executeQuery("INSERT INTO $name VALUES(1,10),(2,20) RETURNING id").use { rows ->
                val ids = buildList { while (rows.next()) add(rows.getInt(1)) }
                assertThat(ids).containsExactlyInAnyOrder(1, 2)
            }
        }
        assertQuery("SELECT id,new_v FROM $catalog.${name}_audit ORDER BY id", "VALUES(1,10),(2,20)")
        assertThatThrownBy { execute(connection, "CREATE TRIGGER unsupported BEFORE INSERT ON $name FOR EACH ROW " +
            "INSERT INTO ${name}_audit VALUES('x',NEW.id,NULL,NEW.v)") }
            .isInstanceOf(SQLException::class.java).hasMessageContaining("BEFORE FOR EACH ROW")
        val tables = computeActual("SHOW TABLES FROM $catalog.triggers").materializedRows.map { it.getField(0).toString() }
        assertThat(tables).doesNotContain("bi", "ai", "ai_order", "au", "ad")
    }

    @Test
    fun testUnsupportedRowUpdateAndReturningRejectWithoutMutation() = withTargets("row_limits", true) { catalog, connection, name ->
        assertThatThrownBy { execute(connection, "CREATE TRIGGER unsupported_update AFTER UPDATE ON $name FOR EACH ROW " +
            "INSERT INTO ${name}_audit VALUES('u',NEW.id,OLD.v,NEW.v)") }
            .isInstanceOf(SQLException::class.java).hasMessageContaining("UPDATE FOR EACH ROW")
        assertThatThrownBy {
            connection.createStatement().use { statement -> statement.executeQuery("INSERT INTO $name VALUES(1,10) RETURNING id").close() }
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("RETURNING")
        assertQuery("SELECT count(*) FROM $catalog.$name", "VALUES CAST(0 AS BIGINT)")
        assertQuery("SELECT count(*) FROM $catalog.${name}_audit", "VALUES CAST(0 AS BIGINT)")
        changed("INSERT INTO $catalog.$name VALUES(1,10)", 1)
    }
}
