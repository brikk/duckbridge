package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.duckdb.DuckDBDriver
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** File-format oracle runs in distinct JVMs: loading two native driver releases together is forbidden. */
class TestStorageCompatibility {
    private val root = Path.of("build/storage-compatibility").toAbsolutePath().also { Files.createDirectories(it) }
    private val legacyJar = Path.of(checkNotNull(System.getProperty("duckbridge.test.legacyJdbcJar")))
    private val currentJar = Path.of(DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
    private data class Probe(val code: Int, val text: String, val fields: Map<String, String>)

    private fun probe(file: Path, version: String, operation: String, compatibility: String = "default", target: Path? = null): Probe {
        val jar = if (version == "1.5.6") legacyJar else currentJar
        val classes = Path.of(StorageCompatibilityProbe::class.java.protectionDomain.codeSource.location.toURI())
        val output = Files.createTempFile(root, "$operation-$version-", ".log")
        val command = listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx256m", "--enable-native-access=ALL-UNNAMED",
            "-cp", "$classes${java.io.File.pathSeparator}$jar", StorageCompatibilityProbe::class.java.name,
            version, file.toString(), operation, compatibility) + listOfNotNull(target?.toString())
        val child = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).describedAs("Storage probe: %s", command).isTrue()
            val text = Files.readString(output)
            val fields = text.lineSequence().filter { it.matches(Regex("[A-Z_]+=.*")) }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return Probe(child.exitValue(), text, fields)
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }

    private fun ok(probe: Probe): Map<String, String> {
        assertThat(probe.code).describedAs(probe.text).isZero()
        if (probe.fields.containsKey("DATA")) {
            assertThat(probe.fields["WIDE"]).isEqualTo("65")
            assertThat(probe.fields["INDEXES"]).isEqualTo("1")
        }
        return probe.fields
    }

    private fun newFile(label: String): Path = Files.createTempDirectory(root, "$label-").resolve("owned.duckdb")
    private fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    @Test
    fun testLegacyFilesStayReadableAfterNewerReadAndCheckpointedWrite() {
        val file = newFile("legacy")
        val seeded = ok(probe(file, "1.5.6", "seed"))
        val before = hash(file)
        val newer = ok(probe(file, "2.0.0", "read"))
        assertThat(newer["DATA"]).isEqualTo(seeded["DATA"])
        assertThat(hash(file)).describedAs("Read-only open must not rewrite the file").isEqualTo(before)
        val written = ok(probe(file, "2.0.0", "write"))
        val older = ok(probe(file, "1.5.6", "read"))
        assertThat(written["FORMAT"]).isEqualTo(seeded["FORMAT"])
        assertThat(older["DATA"]).isEqualTo(written["DATA"]).isNotEqualTo(seeded["DATA"])
    }

    @Test
    fun testNewDefaultFilesRejectOldReadersButExplicitCompatibilityWorks() {
        val modern = newFile("modern")
        val fresh = ok(probe(modern, "2.0.0", "seed"))
        assertThat(fresh["FORMAT"]).isEqualTo("v2.0.0+")
        rejectOldReader(probe(modern, "1.5.6", "read"))
        assertThat(ok(probe(modern, "2.0.0", "read"))["DATA"]).isEqualTo(fresh["DATA"])
        val compatible = newFile("compatible")
        val seed = ok(probe(compatible, "2.0.0", "seed", "v1.5.0"))
        assertThat(seed["FORMAT"]).isEqualTo("v1.5.0+")
        assertThat(ok(probe(compatible, "1.5.6", "read"))["DATA"]).isEqualTo(seed["DATA"])
        val changed = ok(probe(compatible, "2.0.0", "write"))
        assertThat(ok(probe(compatible, "1.5.6", "read"))["DATA"]).isEqualTo(changed["DATA"])
    }

    @Test
    fun testExplicitCopyUpgradeLeavesARestorableOriginal() {
        val original = newFile("upgrade-original")
        val seed = ok(probe(original, "1.5.6", "seed"))
        val backup = original.resolveSibling("backup.duckdb")
        Files.copy(original, backup)
        val fingerprint = hash(original)
        val upgraded = original.resolveSibling("upgraded.duckdb")
        ok(probe(original, "2.0.0", "upgrade", target = upgraded))
        val copied = ok(probe(upgraded, "2.0.0", "read"))
        assertThat(copied["FORMAT"]).isEqualTo("v2.0.0+")
        assertThat(copied["DATA"]).isEqualTo(seed["DATA"])
        assertThat(hash(original)).isEqualTo(fingerprint)
        assertThat(hash(backup)).isEqualTo(fingerprint)
        rejectOldReader(probe(upgraded, "1.5.6", "read"))
        assertThat(ok(probe(backup, "1.5.6", "read"))["DATA"]).isEqualTo(seed["DATA"])
    }

    private fun rejectOldReader(probe: Probe) {
        assertThat(probe.code).describedAs(probe.text).isEqualTo(2) // explicit SQLException, never a native crash
        assertThat(probe.text).contains("SQL_ERROR", "version number 69", "newer version of DuckDB")
    }
}
