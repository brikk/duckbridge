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

import io.airlift.log.Logger
import java.io.IOException
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the `trino_parity.duckdb_extension` binary path at runtime.
 *
 * Resolution order:
 *
 *  1. Callers with an explicit `duckbridge.parity-extension-path` use that directly (handled
 *     by the client/module — not this resolver).
 *  2. Otherwise, look for classpath resources at
 *     `dev/brikk/duckbridge/trino/plugin/duckdb-extensions/<platform>/trino_parity.duckdb_extension`,
 *     plus its `.info` provenance when this is a signed community build. Extract into a PER-PROCESS
 *     PRIVATE directory (`Files.createTempDirectory("trino-duckbridge-")`, owner-only permissions on
 *     POSIX). Signed pairs use DuckDB's standard `<version>/<platform>/` tree and are loaded by name;
 *     unsigned local development builds use a direct path. The directory is never a fixed,
 *     predictable path shared with other users of the host: this file is native code the DuckDB
 *     in the Trino worker will `dlopen`, so a world-writable `/tmp/trino-duckbridge/...` that
 *     another local user could pre-create or swap (TOCTOU) is not acceptable (EV-C4 in
 *     dev-docs/TODO-rectify-from-eval.md).
 *  3. If neither is available, return null — the caller decides how to fail.
 *
 * Signature: DuckDB 2 only trusts a community signature when the extension is loaded from an
 * installed layout carrying its `.info` origin. [isUnsigned] still distinguishes local development
 * builds, which require the explicit `duckbridge.allow-unsigned-extensions=true` opt-in.
 *
 * Platform detection from `os.name` / `os.arch`:
 * ```
 * Mac OS X + aarch64/arm64 -> darwin-arm64
 * Mac OS X + x86_64/amd64  -> darwin-amd64
 * Linux    + x86_64/amd64  -> linux-amd64
 * Linux    + aarch64/arm64 -> linux-arm64
 * Windows  + x86_64/amd64  -> windows-amd64
 * ```
 *
 * The Gradle build's `bundleParityExtension` task writes the host platform's binary plus any
 * cross-built linux variants that exist. Multi-platform bundling needs a CI matrix that builds
 * the extension on each target.
 *
 * P3 note (Quack remote): a server-side execution engine evaluates SQL in a separate process, so
 * this resolver's worker-local extraction is meaningless there. Production servers install and
 * LOAD the signed community extension by name; a configured path is only for a directly loadable
 * server-side development binary.
 */
object TrinoParityExtensionResolver {
    private val log: Logger = Logger.get(TrinoParityExtensionResolver::class.java)

    private const val RESOURCE_DIR: String = "dev/brikk/duckbridge/trino/plugin/duckdb-extensions"
    private const val RESOURCE_FILE: String = "trino_parity.duckdb_extension"
    private const val INFO_FILE: String = "$RESOURCE_FILE.info"
    private const val DUCKDB_VERSION_DIRECTORY: String = "v2.0.0"

    /** Result holder so the cache can distinguish "not resolved yet" from "resolved to nothing". */
    private class Resolution(val extension: BundledExtension?)

    data class BundledExtension(
        val path: String,
        val infoPath: String?,
        val extensionDirectory: String?,
        val duckDbPlatform: String,
        val extractionRoot: String,
    )

    private val cached: AtomicReference<Resolution?> = AtomicReference()
    private val resolveLock: Any = Any()

    /**
     * Returns the absolute path to the bundled extension for the host platform, extracting on
     * first call. Null when no bundled binary matches the host or extraction fails. Cached per
     * process, guarded by a double-checked synchronized region so parallel test/split runners
     * only run one `Files.copy`.
     */
    fun resolveBundledExtension(): BundledExtension? {
        cached.get()?.let { return it.extension }
        return synchronized(resolveLock) {
            val inner = cached.get()
            if (inner != null) {
                inner.extension
            } else {
                val resolved = doResolve()
                cached.set(Resolution(resolved))
                resolved
            }
        }
    }

    fun resolveBundledExtensionPath(): String? = resolveBundledExtension()?.path

    /**
     * Resolve a specific platform's bundled binary, regardless of the running host. Used by test
     * fixtures that need to mount the Linux binary into a Linux container even though the JVM runs
     * on macOS. Result is NOT cached — different platforms have different paths.
     */
    fun resolveBundledExtensionFor(platform: String): BundledExtension? {
        return synchronized(resolveLock) {
            val resourcePath = "$RESOURCE_DIR/$platform/$RESOURCE_FILE"
            val url = TrinoParityExtensionResolver::class.java.classLoader.getResource(resourcePath)
            if (url == null) {
                log.info(
                    "trino_parity: no bundled extension for platform %s (resource %s missing)",
                    platform,
                    resourcePath,
                )
                null
            } else {
                val infoUrl =
                    TrinoParityExtensionResolver::class.java.classLoader.getResource("$RESOURCE_DIR/$platform/$INFO_FILE")
                extractOrNull(url, infoUrl, platform)
            }
        }
    }

    fun resolveBundledExtensionPathFor(platform: String): String? = resolveBundledExtensionFor(platform)?.path

    private fun doResolve(): BundledExtension? {
        val platform =
            detectPlatform() ?: run {
                log.info(
                    "trino_parity: unsupported host platform (os.name=%s, os.arch=%s); no bundled extension available",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch"),
                )
                return null
            }
        val resourcePath = "$RESOURCE_DIR/$platform/$RESOURCE_FILE"
        val url = TrinoParityExtensionResolver::class.java.classLoader.getResource(resourcePath)
        if (url == null) {
            log.info(
                "trino_parity: no bundled extension on classpath for platform %s (resource %s missing). " +
                    "Operators must set duckbridge.parity-extension-path or rebuild the plugin jar with the " +
                    "extension binary present.",
                platform,
                resourcePath,
            )
            return null
        }
        val infoUrl = TrinoParityExtensionResolver::class.java.classLoader.getResource("$RESOURCE_DIR/$platform/$INFO_FILE")
        val extracted = extractOrNull(url, infoUrl, platform)
        if (extracted != null) {
            log.info("trino_parity: extracted bundled extension for platform %s to %s", platform, extracted.path)
        }
        return extracted
    }

    private fun extractOrNull(binaryUrl: URL, infoUrl: URL?, platform: String): BundledExtension? =
        try {
            // KEEP THE BASENAME "trino_parity.duckdb_extension" — DuckDB derives the C entrypoint
            // symbol from the filename's stem (`<stem>_duckdb_cpp_init`). Mangling the filename to
            // encode the platform breaks symbol resolution at LOAD. Disambiguate via the parent dir.
            val root = privateRoot()
            val duckDbPlatform = duckDbPlatform(platform)
            val tempDir =
                if (infoUrl == null) {
                    root.resolve("direct").resolve(platform)
                } else {
                    root.resolve(DUCKDB_VERSION_DIRECTORY).resolve(duckDbPlatform)
                }
            Files.createDirectories(tempDir)
            val target = tempDir.resolve(RESOURCE_FILE)
            extractAtomically(binaryUrl, tempDir, target)
            val infoTarget =
                infoUrl?.let {
                    tempDir.resolve(INFO_FILE).also { info -> extractAtomically(it, tempDir, info) }
                }
            BundledExtension(
                path = target.toAbsolutePath().toString(),
                infoPath = infoTarget?.toAbsolutePath()?.toString(),
                extensionDirectory = if (infoTarget == null) null else root.toAbsolutePath().toString(),
                duckDbPlatform = duckDbPlatform,
                extractionRoot = root.toAbsolutePath().toString(),
            )
        } catch (e: IOException) {
            log.warn(e, "trino_parity: failed to extract bundled extension for platform %s", platform)
            null
        }

    private fun duckDbPlatform(platform: String): String =
        when (platform) {
            "linux-amd64" -> "linux_amd64"
            "linux-arm64" -> "linux_arm64"
            "darwin-amd64" -> "osx_amd64"
            "darwin-arm64" -> "osx_arm64"
            "windows-amd64" -> "windows_amd64"
            else -> error("Unsupported trino_parity platform: $platform")
        }

    /**
     * The process-private extraction root: created once per JVM with an unpredictable name under
     * `java.io.tmpdir` and owner-only permissions (`rwx------`) where the filesystem supports POSIX
     * permissions. Removed on JVM exit on a best-effort basis (the loaded binary is mmapped by
     * DuckDB, so the unlink may be deferred by the OS — harmless).
     */
    private val privateRootRef: AtomicReference<Path?> = AtomicReference()

    @Throws(IOException::class)
    private fun privateRoot(): Path {
        privateRootRef.get()?.let { return it }
        synchronized(resolveLock) {
            privateRootRef.get()?.let { return it }
            val root =
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.createTempDirectory(
                        "trino-duckbridge-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                } else {
                    Files.createTempDirectory("trino-duckbridge-")
                }
            root.toFile().deleteOnExit()
            privateRootRef.set(root)
            return root
        }
    }

    /**
     * True when the extension file at [path] carries no DuckDB signature (the trailing 256-byte
     * signature block of the extension footer is all zeros) — i.e. a locally built binary that
     * DuckDB will refuse to LOAD unless `allow_unsigned_extensions` is on. False for the signed
     * community-extensions build, or when the file cannot be inspected (let DuckDB decide).
     */
    fun isUnsigned(path: String): Boolean =
        try {
            val file = Path.of(path)
            val size = Files.size(file)
            size >= SIGNATURE_BYTES && readTail(file, size).all { it == 0.toByte() }
        } catch (@Suppress("SwallowedException") e: IOException) {
            false
        }

    @Throws(IOException::class)
    private fun readTail(file: Path, size: Long): ByteArray =
        Files.newByteChannel(file).use { ch ->
            ch.position(size - SIGNATURE_BYTES)
            val buf = java.nio.ByteBuffer.allocate(SIGNATURE_BYTES)
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // fill to SIGNATURE_BYTES or EOF
            }
            buf.flip()
            ByteArray(buf.remaining()).also { buf.get(it) }
        }

    /** DuckDB extension footer: the last 256 bytes are the RSA signature (zeros when unsigned). */
    private const val SIGNATURE_BYTES: Int = 256

    /**
     * Extract [url]'s bytes to [target] atomically: stream into a sibling temp file in the same
     * directory, then move it into place (ATOMIC_MOVE where supported). A concurrent reader in this
     * JVM therefore only ever observes the complete binary.
     */
    @Throws(IOException::class)
    private fun extractAtomically(url: URL, tempDir: Path, target: Path) {
        val tmp = Files.createTempFile(tempDir, target.fileName.toString(), ".tmp")
        try {
            url.openStream().use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                log.debug(e, "trino_parity: atomic move unsupported, falling back to plain replace")
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun detectPlatform(): String? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        val osPart =
            when {
                os.contains("mac") || os.contains("darwin") -> "darwin"
                os.contains("linux") -> "linux"
                os.contains("windows") -> "windows"
                else -> return null
            }
        val archPart =
            when (arch) {
                "x86_64", "amd64" -> "amd64"
                "aarch64", "arm64" -> "arm64"
                else -> return null
            }
        return "$osPart-$archPart"
    }
}
