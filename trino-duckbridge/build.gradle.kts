import java.util.zip.ZipFile

plugins {
    id("buildlogic.kotlin.library")
    java
    alias(libs.plugins.detekt)
}

// Default dev version. The release flow (.github/workflows/release.yml) overrides this with
// -Pversion=<derived-from-branch> (e.g. release-483-0.1.0 -> 483-0.1.0), producing the release
// artifact trino-duckbridge-483-0.1.0. A plain script assignment clobbers -Pversion (it runs after
// Gradle applies project properties), so honor an explicit -Pversion when present, else the dev
// default. Version scheme: <trino-major>-<semver> so the Trino ABI is legible in the artifact name.
version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: "483-0.1.0-SNAPSHOT"

// Idiomatic-Kotlin quality gate (detekt 2.0 runs on the JDK 25 daemon; jvmTarget is read
// from the Kotlin compilerOptions). Custom src/test layout, so point detekt at it explicitly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src", "test")
}

repositories {
    mavenCentral()
}

// Use Trino BOM for all managed dependency versions
dependencies {
    // BOM import — controls versions for io.trino, io.airlift, jackson, opentelemetry, etc.
    implementation(platform(libs.trino.bom))
    testImplementation(platform(libs.trino.bom))
    implementation(enforcedPlatform(libs.kotlin.bom)) {
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
    }

    // Core implementation dependencies (versions from BOM)
    implementation("com.google.guava:guava")
    implementation("com.google.inject:guice") {
        artifact {
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
    // Quack remote transport (T3): brikk's pure-JVM JDBC driver for DuckDB's Quack RPC protocol
    // (dev.brikk.duckdb:quack-jdbc — a fixed fork of gizmodata's driver, incl. the LIST/ARRAY
    // element-type fix). No runtime deps of its own (uses JDK 17 HttpClient). Selected by
    // connection-url prefix (jdbc:quack://...) alongside the embedded DuckDB driver.
    implementation(libs.quack.jdbc)
    // Arrow — the experimental execution-engine data plane decodes DuckDB's arrowExportStream
    // batches to Trino Pages via DuckBridgeArrowToPageConverter and feeds Lance/Vortex functions.
    // Versions come solely from the Trino 483 BOM (currently Arrow 19.0.0). Do not add a second,
    // losing Arrow BOM: the former 18.3.0 pin was silently conflict-resolved to 19.0.0 (EV-D1).
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

    // Runtime-only dependencies
    runtimeOnly("io.airlift:log-manager")

    // Test dependencies
    testImplementation("io.airlift:testing")
    testImplementation("io.trino:trino-main")
    // The pushdown plan assertions (QueryAssertions.QueryAssert.isFullyPushedDown / the
    // io.trino.sql.planner.assertions.* plan matchers) and AbstractTestQueryFramework live in the
    // trino-main and trino-testing test-jars.
    testImplementation("io.trino:trino-main") {
        artifact { classifier = "tests" }
    }
    testImplementation("io.trino:trino-testing")
    testImplementation("io.trino:trino-tpch")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers-hosted Quack server for the T3 (quack-jdbc) integration tests.
    testImplementation(libs.testcontainers.core)

    // compileOnly deps also needed at test time
    testCompileOnly("com.fasterxml.jackson.core:jackson-annotations")
    testImplementation("io.airlift:slice")
    testImplementation("io.trino:trino-spi")
}

// ★ Keep engine-provided (parent-first SPI) jars OUT of the plugin dir. trino-base-jdbc /
// plugin-toolkit / airlift-json pull slice + jackson-annotations + opentelemetry-context back in at
// COMPILE scope, so `compileOnly` alone does not remove them from runtimeClasspath — without these
// excludes the assembled plugin dir bundles them and collides with Trino's parent-first classloader.
// (Mirrors the trino-doris-connector assembly proof.)
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

// ---- Bundle trino_parity DuckDB extension into the plugin jar ----
//
// The connector requires the trino_parity.duckdb_extension binary to be
// LOAD-able by the in-process DuckDB (later phases). We bundle every
// locally-built platform variant into the plugin jar's classpath resources,
// keyed by platform name. A runtime resolver (later phase) detects the runtime
// platform and extracts the matching binary.
//
// Source paths (one per platform, each independently optional), relative to the
// repo-root duckdb-trino-parity-extension submodule:
//   duckdb-trino-parity-extension/build/release/extension/...    host platform
//                                                                  (output of `make`)
//   duckdb-trino-parity-extension/build/linux-arm64/release/...  output of `make linux-arm64`
//   duckdb-trino-parity-extension/build/linux-amd64/release/...  output of `make linux-amd64`
//
// Bundled to: parity-extension/dev/brikk/duckbridge/trino/plugin/duckdb-extensions/<platform>/trino_parity.duckdb_extension
//
// Missing binaries are non-fatal at build time; the plugin jar just ships
// without that platform's variant and operators on that platform have to wire
// the path manually (or build the extension first).

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

// (platform-name, source path) — the host build lands in build/release;
// cross-built variants land in build/<platform>/release/.
val parityExtensionSources: List<Pair<String, File>> = listOf(
    hostPlatform to parityExtensionRoot.resolve("build/release/extension/trino_parity/trino_parity.duckdb_extension"),
    "linux-arm64" to parityExtensionRoot.resolve("build/linux-arm64/release/extension/trino_parity/trino_parity.duckdb_extension"),
    "linux-amd64" to parityExtensionRoot.resolve("build/linux-amd64/release/extension/trino_parity/trino_parity.duckdb_extension"),
).distinctBy { it.first }  // host might equal linux-arm64 on Linux CI; dedupe

val parityExtensionResourcePrefix =
    "generated-resources/parity-extension/dev/brikk/duckbridge/trino/plugin/duckdb-extensions"

val bundleParityExtension by tasks.registering {
    description = "Copy every available trino_parity.duckdb_extension into the plugin's classpath resources."
    group = "build"
    val outputs = parityExtensionSources.map { (platform, _) ->
        layout.buildDirectory.file("$parityExtensionResourcePrefix/$platform/trino_parity.duckdb_extension")
    }
    inputs.files(parityExtensionSources.map { it.second }).withPropertyName("sources").optional()
    this.outputs.files(outputs).withPropertyName("bundled")
    doLast {
        var bundled = 0
        for ((platform, source) in parityExtensionSources) {
            if (!source.isFile) {
                logger.lifecycle("trino_parity: $platform binary missing at ${source.relativeToOrSelf(rootProject.projectDir)} — skipping (build it with `(cd duckdb-trino-parity-extension && make ${if (platform == hostPlatform) "" else platform})`).")
                continue
            }
            val target = layout.buildDirectory.file(
                "$parityExtensionResourcePrefix/$platform/trino_parity.duckdb_extension"
            ).get().asFile
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            bundled++
            logger.info("trino_parity: bundled $platform from ${source.relativeToOrSelf(rootProject.projectDir)}")
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

val pluginAssemble by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("trino-plugin/trino-duckbridge-$version"))
    from(tasks.jar)
    from(configurations.runtimeClasspath)
}

// Guard the assembled plugin dir: no engine-provided (parent-first SPI) jar may be bundled, the
// core runtime jars must be present, exactly one guice jar (the `classes` classifier), and the
// connector jar must carry its ServiceLoader registration (a real Trino server discovers the plugin
// via META-INF/services — the programmatic installPlugin used by tests bypasses it, so a missing
// registration is invisible to every suite). Mirrors trino-doris-connector's verifyPluginAssembly.
val verifyPluginAssembly by tasks.registering {
    dependsOn(pluginAssemble)
    val pluginDir = layout.buildDirectory.dir("trino-plugin/trino-duckbridge-$version")
    doLast {
        val jars = pluginDir.get().asFile.listFiles().orEmpty().map { it.name }.sorted()
        check(jars.isNotEmpty()) { "plugin dir is empty: ${pluginDir.get()}" }

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

        val guiceJars = jars.filter { it.startsWith("guice-") }
        check(guiceJars.size == 1 && guiceJars.single().endsWith("-classes.jar")) {
            "expected exactly one guice jar with the 'classes' classifier, got: $guiceJars"
        }

        val connectorJar = pluginDir.get().asFile.listFiles().orEmpty()
            .single { it.name.startsWith("trino-duckbridge-") && it.name.endsWith(".jar") }
        val serviceEntry = "META-INF/services/io.trino.spi.Plugin"
        val registered = ZipFile(connectorJar).use { zip ->
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
