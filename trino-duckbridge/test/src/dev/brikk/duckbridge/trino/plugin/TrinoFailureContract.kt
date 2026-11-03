package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryFailedException
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows

/** Compare actual engine error metadata, never an arbitrary pair of failures. */
internal object TrinoFailureContract {
    fun same(runner: QueryRunner, session: Session, reference: String, production: String, millisOverflow: Boolean = false): String {
        fun failure(sql: String): List<String?> {
            val error = assertThrows(QueryFailedException::class.java) { runner.execute(session, sql) }
            val info = (runner as DistributedQueryRunner).coordinator.queryManager.getFullQueryInfo(error.queryId)
            val code = checkNotNull(info.errorCode).name
            check(code !in setOf("SYNTAX_ERROR", "FUNCTION_NOT_FOUND", "COLUMN_NOT_FOUND", "TYPE_MISMATCH")) { "Invalid failure oracle: $sql" }
            return listOf(code, info.failureInfo?.type, info.failureInfo?.message)
        }
        val expected = failure(reference)
        if (millisOverflow) {
            assertThat(expected[0]).isEqualTo("GENERIC_INTERNAL_ERROR")
            assertThat(expected[1]).isEqualTo("java.lang.IllegalArgumentException")
            assertThat(expected[2]).startsWith("Millis overflow:")
        } else {
            assertThat(expected[2]).containsAnyOf("overflow", "range", "timestamp", "Timestamp", "finite", "infinity")
        }
        val actual = failure(production)
        assertThat(actual).isEqualTo(expected)
        return actual.joinToString(" | ")
    }
}
