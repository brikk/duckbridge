import java.io.RandomAccessFile
import java.util.zip.ZipFile

plugins {
    id("buildlogic.kotlin.library")
    java
    alias(libs.plugins.detekt)
}

val targetTrino = providers.gradleProperty("trinoVersion").orElse(libs.versions.trino).get()
check(targetTrino in setOf("483", "484")) { "Supported Trino build targets are 483 and 484; got $targetTrino" }
version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: "$targetTrino-0.3.0-SNAPSHOT"
check(version.toString().startsWith("$targetTrino-")) { "Artifact version $version must identify the Trino $targetTrino ABI" }

val targetSource = layout.buildDirectory.dir("generated/sources/trino-target")
val generateTrinoTarget by tasks.registering {
    inputs.property("trinoVersion", targetTrino)
    outputs.dir(targetSource)
    doLast {
        val source = targetSource.get().file("dev/brikk/duckbridge/trino/plugin/DuckBridgeTrinoTarget.kt").asFile
        source.parentFile.mkdirs()
        source.writeText("package dev.brikk.duckbridge.trino.plugin\n\ninternal object DuckBridgeTrinoTarget { const val VERSION = $targetTrino }\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(targetSource) }
tasks.named("compileKotlin") { dependsOn(generateTrinoTarget) }

// Custom src/test layout, so point detekt at it explicitly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src", "test")
}

repositories {
    mavenCentral()
}

// Test-only, separate-process storage oracle. Never added to a connector/test runtime classpath.
val legacyStorageDriver by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(legacyStorageDriver.name, "org.duckdb:duckdb_jdbc:1.5.6.0")
    implementation(platform("io.trino:trino-root:$targetTrino"))
    testImplementation(platform("io.trino:trino-root:$targetTrino"))
    implementation(enforcedPlatform(libs.kotlin.bom)) {
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
    }

    implementation("com.google.guava:guava")
    implementation(if (targetTrino == "483") "com.google.inject:guice" else "io.airlift:guice") {
        if (targetTrino == "483") artifact {
            classifier = "classes"
        }
    }
    implementation("io.airlift:bootstrap")
    implementation("io.airlift:configuration")
    implementation("io.airlift:json")
    implementation("io.airlift:log")
    implementation("io.airlift:units")
    implementation("io.trino:trino-base-jdbc")
    implementation("io.trino:trino-plugin-toolkit")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation(libs.duckdb.jdbc)
    // Pure-JVM JDBC driver for DuckDB's Quack RPC protocol (a fork of gizmodata's driver),
    // selected by the jdbc:quack:// connection-url prefix.
    implementation(libs.quack.jdbc)
    // Arrow versions come solely from the Trino BOM. A second Arrow BOM loses conflict resolution
    // silently.
    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.core)
    implementation(libs.arrow.data)
    runtimeOnly(libs.arrow.memory.netty)

    // SPI dependencies — provided by Trino at runtime (compileOnly = Maven provided)
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("io.airlift:slice")
    compileOnly("io.opentelemetry:opentelemetry-api")
    compileOnly("io.opentelemetry:opentelemetry-context")
    compileOnly("io.trino:trino-spi")

    runtimeOnly("io.airlift:log-manager")

    testImplementation("io.airlift:testing")
    testImplementation("io.trino:trino-main")
    // Plan assertions and AbstractTestQueryFramework live in the trino-main and trino-testing test-jars.
    testImplementation("io.trino:trino-main") {
        artifact { classifier = "tests" }
    }
    testImplementation("io.trino:trino-testing")
    testImplementation("io.trino:trino-tpch")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers-hosted Quack server for the quack-jdbc integration tests.
    testImplementation(libs.testcontainers.core)
    testImplementation("org.testcontainers:testcontainers-toxiproxy:${libs.versions.containers.get()}")

    // compileOnly deps also needed at test time
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("io.airlift:slice")
    testImplementation("io.trino:trino-spi")
}

// Keep engine-provided (parent-first SPI) jars OUT of the plugin dir. trino-base-jdbc /
// plugin-toolkit / airlift-json pull slice + jackson-annotations + opentelemetry-context back in at
// COMPILE scope, so `compileOnly` alone does not remove them from runtimeClasspath — without these
// excludes the assembled plugin dir bundles them and collides with Trino's parent-first classloader.
configurations.named("runtimeClasspath") {
    exclude(group = "io.trino", module = "trino-spi")
    exclude(group = "io.airlift", module = "slice")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-annotations")
    exclude(group = "io.opentelemetry", module = "opentelemetry-api")
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    exclude(group = "io.opentelemetry", module = "opentelemetry-context")
    // NOTE: do NOT exclude io.opentelemetry by group — -jdbc/-instrumentation/-semconv are
    // plugin-local and required by trino-base-jdbc.
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Strict Quack acceptance gates, run outside JUnit. The bridge diagnostic is a separate control
// task, never an acceptance route.
listOf("qualifyQuackStreaming" to "streaming", "qualifyQuackCancel" to "cancel",
    "qualifyQuackDisconnect" to "disconnect", "qualifyQuackCancelPartition" to "cancel-partition",
    "qualifyQuackLeaseReaping" to "lease-reaping", "probeQuackStreamingBridge" to "streaming-bridge-control").forEach { (name, gate) ->
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = if (gate == "streaming-bridge-control") "Diagnostic bridge-route control; NOT published-port acceptance"
            else "Strict Quack $gate acceptance gate"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.brikk.duckbridge.trino.plugin.TestQuackNetworkLifecycle")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        maxHeapSize = "2g"
        args(gate)
    }
}

tasks.register<JavaExec>("benchmarkScans") {
    group = "verification"
    description = "Test-only embedded scalar scan benchmark; never changes connector transport defaults"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.trino.plugin.ScanBenchmark")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "1g"
    jvmArgs("--add-modules=jdk.incubator.vector", "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    args(layout.buildDirectory.dir("scan-benchmark").get().asFile.absolutePath)
}

// ---- Bundle trino_parity DuckDB extension into the plugin jar ----
//
// DuckDB 2 community signatures require the binary AND its DuckDB-generated .info provenance, so
// signed inputs are bundled as pairs; local unsigned builds are a binary-only fallback.
//
// Local development source paths (each independently optional), relative to the parity submodule:
//   duckdb-trino-parity-extension/build/release/extension/...    host platform
//                                                                  (output of `make`)
//   duckdb-trino-parity-extension/build/linux-arm64/release/...  output of `make linux-arm64`
//   duckdb-trino-parity-extension/build/linux-amd64/release/...  output of `make linux-amd64`
//
// Community release inputs:
//   duckdb-trino-parity-extension/build/community/<platform>/trino_parity.duckdb_extension{,.info}
//
// Missing platform pairs are non-fatal for local builds. The release workflow populates and verifies
// every published native platform before assembling its artifact.

val parityExtensionRoot: File =
    rootProject.projectDir.resolve("duckdb-trino-parity-extension")

val hostPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("mac") || os.contains("darwin") -> "darwin"
        os.contains("linux") -> "linux"
        os.contains("windows") -> "windows"
        else -> "unknown"
    }
    val archPart = when (arch) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> "unknown"
    }
    "$osPart-$archPart"
}

data class ParityExtensionSource(
    val platform: String,
    val communityDirectory: File,
    val localBinary: File?,
)

val communityRoot = parityExtensionRoot.resolve("build/community")
val localParityBinaries = mutableMapOf(
    "linux-arm64" to parityExtensionRoot.resolve("build/linux-arm64/release/extension/trino_parity/trino_parity.duckdb_extension"),
    "linux-amd64" to parityExtensionRoot.resolve("build/linux-amd64/release/extension/trino_parity/trino_parity.duckdb_extension"),
)
localParityBinaries[hostPlatform] =
    parityExtensionRoot.resolve("build/release/extension/trino_parity/trino_parity.duckdb_extension")
val parityExtensionSources =
    (listOf("linux-amd64", "linux-arm64", "darwin-arm64", "windows-amd64", hostPlatform))
        .distinct()
        .map { platform ->
            ParityExtensionSource(platform, communityRoot.resolve(platform), localParityBinaries[platform])
        }

val parityExtensionResourcePrefix =
    "generated-resources/parity-extension/dev/brikk/duckbridge/trino/plugin/duckdb-extensions"

fun File.isUnsignedDuckDbExtension(): Boolean {
    val signatureBytes = 256
    if (length() < signatureBytes) return false
    return RandomAccessFile(this, "r").use { file ->
        file.seek(length() - signatureBytes)
        ByteArray(signatureBytes).also(file::readFully).all { it == 0.toByte() }
    }
}

val bundleParityExtension by tasks.registering {
    description = "Bundle available signed trino_parity binary/.info pairs or local unsigned binaries."
    group = "build"
    val sourceFiles = parityExtensionSources.flatMap { source ->
        listOf(
            source.communityDirectory.resolve("trino_parity.duckdb_extension"),
            source.communityDirectory.resolve("trino_parity.duckdb_extension.info"),
        ) + listOfNotNull(source.localBinary)
    }
    inputs.files(sourceFiles).withPropertyName("sources").optional()
    outputs.dir(layout.buildDirectory.dir(parityExtensionResourcePrefix)).withPropertyName("bundled")
    doLast {
        val outputRoot = layout.buildDirectory.dir(parityExtensionResourcePrefix).get().asFile
        outputRoot.deleteRecursively()
        var bundled = 0
        for (source in parityExtensionSources) {
            val communityBinary = source.communityDirectory.resolve("trino_parity.duckdb_extension")
            val communityInfo = source.communityDirectory.resolve("trino_parity.duckdb_extension.info")
            val hasCommunityBinary = communityBinary.isFile
            val hasCommunityInfo = communityInfo.isFile
            if (hasCommunityBinary != hasCommunityInfo) {
                throw GradleException(
                    "trino_parity: incomplete signed pair for ${source.platform}; binary and .info must both exist"
                )
            }
            val targetDirectory = outputRoot.resolve(source.platform)
            when {
                hasCommunityBinary -> {
                    targetDirectory.mkdirs()
                    communityBinary.copyTo(targetDirectory.resolve(communityBinary.name), overwrite = true)
                    communityInfo.copyTo(targetDirectory.resolve(communityInfo.name), overwrite = true)
                    logger.info("trino_parity: bundled signed community pair for ${source.platform}")
                }
                source.localBinary?.isFile == true -> {
                    if (!source.localBinary.isUnsignedDuckDbExtension()) {
                        throw GradleException(
                            "trino_parity: signed binary for ${source.platform} has no .info provenance; " +
                                "run .github/scripts/fetch-parity-extension.sh on that platform"
                        )
                    }
                    targetDirectory.mkdirs()
                    source.localBinary.copyTo(
                        targetDirectory.resolve("trino_parity.duckdb_extension"),
                        overwrite = true,
                    )
                    logger.info("trino_parity: bundled local binary for ${source.platform}")
                }
                else -> {
                    logger.lifecycle("trino_parity: ${source.platform} binary missing — skipping")
                    continue
                }
            }
            bundled++
        }
        if (bundled == 0) {
            logger.lifecycle("trino_parity: NO platform binaries bundled — plugin jar will require the parity-extension path to be set at deploy time.")
        }
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources/parity-extension"))
}

tasks.named("processResources") {
    dependsOn(bundleParityExtension)
}

tasks.test {
    systemProperty("duckbridge.test.trinoVersion", targetTrino)
    systemProperty("duckbridge.test.legacyJdbcJar", legacyStorageDriver.singleFile.absolutePath)
    // Child-JVM raw-driver crash controls must load the exact same resolved test artifacts.
    systemProperty("duckbridge.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    useJUnitPlatform {
        (project.findProperty("excludeTags") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { excludeTags(*it.toTypedArray()) }
    }

    maxHeapSize = "3g"
    minHeapSize = "3g"

    jvmArgs(
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:-OmitStackTraceInFastThrow",
        // Trino's BlockEncodingSimdSupport (via trino-main) needs the incubator vector module.
        "--add-modules=jdk.incubator.vector",
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        // Trino's server bootstrap reaches into private java.base internals.
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}

// This is an owned generated staging directory. Copy retains old dependency versions across a
// repin; Sync makes the packaged classpath exactly the resolved graph (including Quack upgrades).
val pluginAssemble by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("trino-plugin/trino-duckbridge-$version"))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from("RELEASE-NOTES.md", "README-pushdown-reference.md")
}

// Guard the assembled plugin dir. The ServiceLoader check matters because tests install the plugin
// programmatically, so a missing META-INF/services registration is invisible to every suite.
val verifyPluginAssembly by tasks.registering {
    dependsOn(pluginAssemble)
    val pluginDir = layout.buildDirectory.dir("trino-plugin/trino-duckbridge-$version")
    doLast {
        val files = pluginDir.get().asFile.listFiles().orEmpty().map { it.name }.sorted()
        val jars = files.filter { it.endsWith(".jar") }
        check(jars.isNotEmpty()) { "plugin dir is empty: ${pluginDir.get()}" }
        check(files.containsAll(listOf("RELEASE-NOTES.md", "README-pushdown-reference.md"))) {
            "plugin package must include release limitations and the pushdown reference"
        }

        val forbiddenModules = listOf(
            "trino-spi",
            "slice",
            "jackson-annotations",
            "opentelemetry-api",
            "opentelemetry-api-incubator",
            "opentelemetry-context",
        )
        val offenders = jars.filter { jar ->
            forbiddenModules.any { module -> Regex("^${Regex.escape(module)}-\\d.*\\.jar$").matches(jar) }
        }
        check(offenders.isEmpty()) { "provided/parent-first SPI jars must not be bundled: $offenders" }

        val requiredPrefixes = listOf(
            "trino-base-jdbc-",
            "trino-plugin-toolkit-",
            "duckdb_jdbc-",
            "quack-jdbc-",
            "arrow-vector-",
        )
        val missing = requiredPrefixes.filter { prefix -> jars.none { it.startsWith(prefix) } }
        check(missing.isEmpty()) { "expected bundled jars missing (prefixes): $missing; got $jars" }

        for (module in listOf("trino-base-jdbc", "trino-plugin-toolkit")) {
            check(jars.filter { it.startsWith("$module-") } == listOf("$module-$targetTrino.jar")) {
                "plugin must contain exactly the Trino $targetTrino $module runtime"
            }
        }

        for (prefix in listOf("duckdb_jdbc-", "quack-jdbc-")) {
            val expected = configurations.runtimeClasspath.get().files
                .single { it.name.startsWith(prefix) }.name
            val packaged = jars.filter { it.startsWith(prefix) }
            check(packaged == listOf(expected)) {
                "expected exactly the resolved driver $expected, got: $packaged"
            }
        }

        val guiceJars = jars.filter { it.startsWith("guice-") }
        val correctGuiceForm = guiceJars.singleOrNull()?.let { it.endsWith("-classes.jar") == (targetTrino == "483") } ?: false
        check(correctGuiceForm) {
            "expected one matching Guice runtime (Google classes for 483, Airlift main for 484), got: $guiceJars"
        }

        val connectorJar = pluginDir.get().asFile.listFiles().orEmpty()
            .single { it.name.startsWith("trino-duckbridge-") && it.name.endsWith(".jar") }
        val serviceEntry = "META-INF/services/io.trino.spi.Plugin"
        val registered = ZipFile(connectorJar).use { zip ->
            check(zip.entries().asSequence().none {
                it.name.startsWith("dev/brikk/duckbridge/trino/plugin/ScanBenchmark") ||
                    it.name.startsWith("dev/brikk/duckbridge/trino/plugin/TestScanBenchmark")
            }) { "test-only benchmark classes must not be packaged in the connector" }
            zip.getEntry(serviceEntry)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().readText().trim()
            }
        }
        check(registered == "dev.brikk.duckbridge.trino.plugin.DuckBridgePlugin") {
            "connector jar must register DuckBridgePlugin via $serviceEntry; found: $registered"
        }

        logger.lifecycle("verifyPluginAssembly OK: ${jars.size} jars, no provided/parent-first SPI jars, ServiceLoader registration present")
    }
}

tasks.build {
    dependsOn(pluginAssemble)
    dependsOn(verifyPluginAssembly)
}
