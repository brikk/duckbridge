package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.text.Normalizer

/** Independent JDK oracle for the actual bundled binary, not a Unicode-version-label comparison. */
class TestUnicodeVersionParity {
    @Test
    fun testAllUnicodeScalarsAgainstTheCurrentJdkProfile() {
        assertThat(Runtime.version().feature()).describedAs("Qualified Trino 483 Unicode profile is JDK 25").isEqualTo(25)
        val extension = checkNotNull(TrinoParityExtensionResolver.resolveBundledExtension())
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(extension.path)))
            .joinToString("") { "%02x".format(it) }
        println("UNICODE binary=${extension.path} SHA256=$hash JDK=${Runtime.version()}")
        DriverManager.getConnection("jdbc:duckdb:", DuckBridgeQueryRunner.duckJdbcProperties()).use { connection ->
            TrinoFunctionAliases.loadBundled(connection, extension)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version(),extension_version FROM duckdb_extensions() WHERE extension_name='trino_parity' AND loaded",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                    assertThat(rows.getString(2)).isEqualTo("v0.5.1")
                }
                statement.executeQuery(
                    "SELECT cp,trino_lower(s),trino_upper(s),trino_trim(s),trino_ltrim(s),trino_rtrim(s)," +
                        "trino_normalize(s),trino_trim(s||'x'||s),trino_ltrim(s||'x'||s),trino_rtrim(s||'x'||s) " +
                        "FROM (SELECT cp,chr(cp::INTEGER) s FROM range(1114112) t(cp) WHERE cp NOT BETWEEN 55296 AND 57343)",
                ).use { rows ->
                    var count = 0
                    val failures = mutableMapOf<Int, Int>()
                    val examples = mutableListOf<String>()
                    while (rows.next()) {
                        val point = rows.getInt(1)
                        expected(point).forEachIndexed { index, value ->
                            val actual = rows.getString(index + 2)
                            if (actual != value) {
                                failures.merge(index, 1, Int::plus)
                                if (examples.size < 12) examples.add("U+${point.toString(16)} check=$index expected=$value actual=$actual")
                            }
                        }
                        count++
                    }
                    println("UNICODE scalars=$count checks=9 failures=$failures examples=$examples")
                    assertThat(count).isEqualTo(1_112_064)
                    assertThat(failures).describedAs("JDK scalar/edge parity: %s", examples).isEmpty()
                }
            }
        }
    }

    private fun expected(point: Int): List<String> {
        val text = UnicodeVersionCases.text(point)
        val whitespace = Character.isWhitespace(point)
        val trimmed = if (whitespace) "" else text
        return listOf(
            UnicodeVersionCases.text(Character.toLowerCase(point)), UnicodeVersionCases.text(Character.toUpperCase(point)),
            trimmed, trimmed, trimmed, Normalizer.normalize(text, Normalizer.Form.NFC),
            if (whitespace) "x" else text + "x" + text,
            if (whitespace) "x" + text else text + "x" + text,
            if (whitespace) text + "x" else text + "x" + text,
        )
    }
}
