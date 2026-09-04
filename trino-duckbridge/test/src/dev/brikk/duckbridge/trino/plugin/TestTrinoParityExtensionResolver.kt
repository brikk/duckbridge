/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * EV-C4: the bundled native extension is extracted to a process-private directory (never a fixed,
 * predictable path another local user could pre-create or swap), and signature verification stays
 * ON by default — an unsigned local build is refused unless the operator opts in explicitly.
 */
class TestTrinoParityExtensionResolver {
    @Test
    fun bundledExtensionIsExtractedIntoAPrivateUnpredictableDirectory() {
        val path = TrinoParityExtensionResolver.resolveBundledExtensionPath()
        assumeTrue(path != null, "no bundled trino_parity for this platform — build or fetch it first")
        val file = Path.of(path!!)
        assertThat(file.fileName.toString()).isEqualTo("trino_parity.duckdb_extension")
        // <tmpdir>/trino-duckbridge-<random>/<platform>/trino_parity.duckdb_extension
        val root = file.parent.parent
        assertThat(root.fileName.toString()).startsWith("trino-duckbridge-")
        assertThat(root.fileName.toString()).isNotEqualTo("trino-duckbridge") // the old fixed, shared path
        assertThat(root.parent).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath())
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            val perms = Files.getPosixFilePermissions(root)
            assertThat(perms)
                .`as`("extraction root must be owner-only")
                .doesNotContainAnyElementsOf(
                    listOf(
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                    ),
                )
        }
        // Idempotent within the process: the same path comes back, no second extraction root.
        assertThat(TrinoParityExtensionResolver.resolveBundledExtensionPath()).isEqualTo(path)
    }

    @Test
    fun isUnsignedReadsTheSignatureFooter() {
        val dir = Files.createTempDirectory("ext-sig-")
        try {
            // 600 bytes of payload + a 512-byte footer whose trailing 256 bytes (the signature) are zero.
            val unsigned = dir.resolve("trino_parity.duckdb_extension")
            Files.write(unsigned, ByteArray(600) { 1 } + ByteArray(256) { 2 } + ByteArray(256))
            assertThat(TrinoParityExtensionResolver.isUnsigned(unsigned.toString())).isTrue()

            val signed = dir.resolve("signed.duckdb_extension")
            Files.write(signed, ByteArray(600) { 1 } + ByteArray(256) { 2 } + ByteArray(256) { 7 })
            assertThat(TrinoParityExtensionResolver.isUnsigned(signed.toString())).isFalse()

            // Too small to carry a footer, or missing: not claimed unsigned (DuckDB decides).
            val tiny = dir.resolve("tiny")
            Files.write(tiny, ByteArray(10))
            assertThat(TrinoParityExtensionResolver.isUnsigned(tiny.toString())).isFalse()
            assertThat(TrinoParityExtensionResolver.isUnsigned(dir.resolve("missing").toString())).isFalse()
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun allowUnsignedExtensionsDefaultsToFalse() {
        // Signature verification is ON unless an operator opts out (EV-C4).
        assertThat(DuckBridgeConfig().isAllowUnsignedExtensions).isFalse()
        assertThat(DuckBridgeConfig().setAllowUnsignedExtensions(true).isAllowUnsignedExtensions).isTrue()
    }
}
