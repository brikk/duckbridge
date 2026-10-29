import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    id("buildlogic.kotlin.library")
    alias(libs.plugins.detekt)
}

// Doris connector-SPI baseline this module compiles against (see doris-patches/BASELINE).
// The plugin jar is versioned independently of the Doris pin.
version = "0.0.1-ALPHA"

// The Doris FE runs JDK 17 with a curated jar set, and the plugin runs INSIDE the FE, so
// this module targets JDK 17 — unlike the rest of the repo, which is JDK 25 (see
// buildlogic.kotlin.common: jvmToolchain(25)). We override the toolchain here to 17 so both
// the Kotlin bytecode AND the test JVM match the FE ABI. Gradle's foojay-resolver
// (settings.gradle.kts) downloads the 17 toolchain on demand — no local JDK 17 install
// needed. (doris-ducklake instead emits 17 bytecode on the tree-wide 25 toolchain; we take
// the cleaner per-module-toolchain route now that this is a standalone repo.)
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Idiomatic-Kotlin quality gate, consistent with :trino-duckbridge (detekt 2.0, shared config).
// Custom src / test/src layout (from buildlogic.kotlin.common), so point detekt at it explicitly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src", "test/src", "audit/src")
}

// Published metadata/transpiler libraries are audit tools, not connector runtime dependencies.
// In particular their Kotlin 2.4 runtime must not replace the FE/plugin's qualified Kotlin closure.
val functionAudit = sourceSets.create("functionAudit")
kotlin.sourceSets.named("functionAudit") { kotlin.srcDir("audit/src") }
val brikkSqlAuditVersion = providers.gradleProperty("brikkSqlAuditVersion").orNull
val functionAuditDorisPin = rootProject.file("doris-patches/BASELINE").readLines()
    .single { it.startsWith("PIN_SHA=") }.substringAfter('=')
val functionAuditDorisVersion = "doris-0.0.0-${functionAuditDorisPin.take(11)}"

// PROJECT-LOCAL Doris artifact repository — the self-contained bootstrap.
//
// The Doris fe-connector-spi / fe-thrift artifacts are built from OUR pinned
// Doris baseline (see doris-patches/BASELINE) by `tools/doris-baseline.sh --install-spi-jars`,
// which `mvn install`s them into `doris-duckbridge/doris-m2/` (gitignored). We resolve them from
// THAT directory — NOT `mavenLocal()`/`~/.m2`.
//
// Why not mavenLocal(): `~/.m2` is shared. A DIFFERENT project (doris-ducklake) publishes the same
// `org.apache.doris:*:1.2-SNAPSHOT` SNAPSHOT coordinates from a DIFFERENT pin — last-build-wins
// clobbering. A project-local repo isolates us from ~/.m2 and from doris-ducklake completely, and
// makes a fresh clone buildable end-to-end with one bootstrap command.
//
// Scoped to the org.apache.doris group via exclusiveContent: doris-m2 also accumulates the
// transitive deps maven pulls during install, but gradle sources ONLY org.apache.doris from it —
// everything else keeps coming from Maven Central.
val dorisLocalRepo = layout.projectDirectory.dir("doris-m2")

repositories {
    exclusiveContent {
        forRepository { maven { url = uri(dorisLocalRepo) } }
        filter { includeGroup("org.apache.doris") }
    }
    mavenCentral()
}

// SPI jars are 1.2-SNAPSHOT, built from our pinned baseline into doris-m2/ by the bootstrap.
val dorisVersion = "1.2-SNAPSHOT"

// Actionable pre-bootstrap failure: if the SPI jars aren't in doris-m2/ yet, fail with the exact
// command to run rather than a cryptic "could not resolve org.apache.doris:...". Checks for the
// spi jar (the anchor artifact) at its Maven layout path under doris-m2/. (Upstream #66407 merged
// fe-connector-api INTO fe-connector-spi — there is no fe-connector-api artifact anymore.)
val dorisSpiAnchorJar = dorisLocalRepo.file(
    "org/apache/doris/fe-connector-spi/$dorisVersion/fe-connector-spi-$dorisVersion.jar",
)
val bootstrapHint =
    "Doris SPI jars are not bootstrapped. Run:\n\n" +
        "    tools/doris-baseline.sh --install-spi-jars\n\n" +
        "This builds fe-connector-spi / fe-thrift from the pinned Doris " +
        "baseline (doris-patches/BASELINE) into doris-duckbridge/doris-m2/ (JDK 17 required). " +
        "See doris-patches/PATCHES.md §Bootstrap."

// Fail loud, actionable, and BEFORE the cryptic "could not resolve org.apache.doris:..." — which
// otherwise surfaces during dependency resolution, before any task's doFirst runs. We hook the
// task graph: if any task that needs the SPI jars (compile/test) is scheduled and doris-m2/ isn't
// bootstrapped, fail with the exact bootstrap command. Tasks that don't touch the jars (help,
// tasks, clean, dependencies) are unaffected.
val dorisDependentTaskNames = setOf("compileKotlin", "compileTestKotlin", "test", "detekt")
gradle.taskGraph.whenReady {
    val needsJars = allTasks.any {
        it.project.path == project.path && it.name in dorisDependentTaskNames
    }
    if (needsJars && !dorisSpiAnchorJar.asFile.exists()) {
        throw GradleException(bootstrapHint)
    }
}

dependencies {
    add(functionAudit.implementationConfigurationName, brikkSqlAuditVersion?.let { "dev.brikk.house:brikk-sql-jvm:$it" } ?: libs.brikk.sql)
    add(functionAudit.implementationConfigurationName, brikkSqlAuditVersion?.let { "dev.brikk.house:brikk-sql-metadata-jvm:$it" } ?: libs.brikk.sql.metadata)
    add(functionAudit.runtimeOnlyConfigurationName, libs.duckdb.jdbc)
    add(functionAudit.runtimeOnlyConfigurationName, libs.doris.quack.jdbc)
    add(functionAudit.runtimeOnlyConfigurationName, "org.mariadb.jdbc:mariadb-java-client:3.0.9")
    add(functionAudit.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // FE supplies these via the parent classloader at runtime — compile-only, so the plugin jar
    // ships no second copy of the SPI/thrift classes (a duplicate would LinkageError across the
    // SPI boundary).
    // #66407 merged fe-connector-api into fe-connector-spi: fe-connector-spi now carries the whole
    // connector contract (org.apache.doris.connector.spi.*, incl. the former .api types).
    compileOnly("org.apache.doris:fe-connector-spi:$dorisVersion")
    compileOnly("org.apache.doris:fe-thrift:$dorisVersion")
    compileOnly("org.apache.doris:fe-foundation:$dorisVersion")

    // quack-jdbc: brikk's pure-JVM DuckDB/Quack driver (dev.brikk.duckdb:quack-jdbc, a fixed fork of
    // gizmodata's) (jdbc:quack://...). The FE resolves
    // schemas/tables/columns through it at plan time; the BE loads it in its own JVM to run the
    // scan. Version from the repo-wide catalog. Bundled in the plugin zip (the FE classloader has
    // no such driver of its own).
    implementation(libs.doris.quack.jdbc)

    // Tests instantiate the provider / connector, so the SPI types are needed at test time too.
    testImplementation("org.apache.doris:fe-connector-spi:$dorisVersion")
    testImplementation("org.apache.doris:fe-thrift:$dorisVersion")
    testImplementation("org.apache.doris:fe-foundation:$dorisVersion")
    // junit / assertj / kotlin-test come from buildlogic.kotlin.common.

    // P4 metadata probe + the over-Quack integration tests spin up a real DuckDB/Quack server
    // (testcontainers) and talk to it through quack-jdbc — the same driver the FE uses at plan
    // time. quack-jdbc is `implementation` above (bundled in the plugin); tests need it on the
    // test classpath explicitly too.
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.doris.quack.jdbc)
    // Match the pinned Doris BE scanner's pool library; never bundle it into the FE plugin.
    testImplementation("com.zaxxer:HikariCP:6.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The FE's ApiVersionGate compares this provider JAR's manifest major with the kernel's
// META-INF/doris/connector-plugin-api-version.properties. An absent or stale major is rejected.
// API 24 matches doris-patches/BASELINE; new write/streaming/cache/partition capabilities remain
// disabled. TestDorisApi24Contract pins the compile resource and the safe runtime defaults.
tasks.jar {
    manifest {
        attributes("Doris-Connector-Plugin-Api-Version" to "24.0")
    }
}

// Plugin zip: a flat lib/ layout with this jar + every runtime dep the FE parent classloader
// doesn't already supply (mirrors doris-ducklake / fe-connector-iceberg plugin-zip.xml). The
// FE-provided SPI/thrift jars are excluded so the plugin doesn't bundle a shadowing copy.
val pluginZip by tasks.registering(Zip::class) {
    dependsOn(tasks.named("jar"))
    archiveBaseName.set("doris-duckbridge")
    archiveClassifier.set("plugin")

    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) {
        into("lib")
        exclude("fe-connector-spi-*.jar")
        exclude("fe-thrift-*.jar")
    }
}

tasks.named("assemble") {
    dependsOn(pluginZip)
}

val verifyPublishedAuditBytecode by tasks.registering {
    group = "verification"
    description = "Reject published brikk-sql audit libraries requiring a JVM newer than Doris's Java 17"
    doLast {
        val libraries = functionAudit.runtimeClasspath.files.filter { it.name.startsWith("brikk-sql-") && it.extension == "jar" }
        check(libraries.any { it.name.startsWith("brikk-sql-jvm") } &&
            libraries.any { it.name.startsWith("brikk-sql-metadata-jvm") }) {
            "Both published JVM audit libraries must be resolved before checking Java-17 compatibility"
        }
        val offenders = buildList {
            for (file in libraries) {
                ZipFile(file).use { jar ->
                    for (entry in jar.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }) {
                        val major = DataInputStream(jar.getInputStream(entry)).use { input ->
                            input.readInt()
                            input.readUnsignedShort()
                            input.readUnsignedShort()
                        }
                        if (major > 61) add("${file.name}: ${entry.name} requires classfile $major")
                    }
                }
            }
        }
        check(offenders.isEmpty()) {
            "Published audit libraries must run on Java 17; publish a compatible artifact, do not bypass Doris's JVM gate. " +
                offenders.take(5).joinToString()
        }
    }
}

val exportFunctionMetadata by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Export published brikk-sql catalogs and capture the real DuckDB-2 JDBC registry on Java 17"
    dependsOn(functionAudit.classesTaskName, verifyPublishedAuditBytecode)
    classpath = functionAudit.runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.doris.audit.PublishedFunctionAudit")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "512m"
    val output = providers.gradleProperty("functionAuditOutput").orNull?.let(::file)
        ?: layout.buildDirectory.dir("function-audit/metadata").get().asFile
    args(output.absolutePath, functionAuditDorisVersion, libs.versions.trino.get())
    outputs.dir(output)
    // This task is a live capture, not just a copy of the bundled metadata.
    outputs.upToDateWhen { false }
}

val auditGeneratedSql by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compare published SQL lowerings with the exact-pin live Doris oracle and DuckDB using real JDBC result metadata"
    dependsOn(exportFunctionMetadata)
    classpath = functionAudit.runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.doris.audit.GeneratedSqlAudit")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "512m"
    val metadata = providers.gradleProperty("functionAuditOutput").orNull?.let(::file)
        ?: layout.buildDirectory.dir("function-audit/metadata").get().asFile
    args(metadata.absolutePath,
        providers.gradleProperty("functionAuditDorisUrl").getOrElse("jdbc:mariadb://127.0.0.1:9030"),
        functionAuditDorisVersion)
}

val auditOverlayTypes by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verify native Doris/DuckDB OVERLAY values, strict NULLs and actual JDBC return contracts"
    dependsOn(functionAudit.classesTaskName)
    classpath = functionAudit.runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.doris.audit.OverlayTypeAudit")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "256m"
    args(layout.buildDirectory.file("function-audit/overlay-types.json").get().asFile.absolutePath,
        providers.gradleProperty("functionAuditDorisUrl").getOrElse("jdbc:mariadb://127.0.0.1:9030"),
        functionAuditDorisVersion)
}

val auditJsonMutations by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compare native JSON INSERT/REMOVE paths, duplicate keys, SQL/JSON NULL and errors without enabling pushdown"
    dependsOn(functionAudit.classesTaskName, verifyPublishedAuditBytecode)
    classpath = functionAudit.runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.doris.audit.JsonMutationAudit")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "512m"
    args(layout.buildDirectory.file("function-audit/json-mutations.json").get().asFile.absolutePath,
        providers.gradleProperty("functionAuditDorisUrl").getOrElse("jdbc:mariadb://127.0.0.1:9030"),
        functionAuditDorisVersion)
}

val auditBoundaryTypes by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Capture DuckDB-2 NULL/nested/temporal metadata and getters on independent JDBC and Quack sessions"
    dependsOn(functionAudit.classesTaskName, verifyPublishedAuditBytecode)
    classpath = functionAudit.runtimeClasspath
    mainClass.set("dev.brikk.duckbridge.doris.audit.DuckDbBoundaryAudit")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "512m"
    args(layout.buildDirectory.file("function-audit/boundary-types.json").get().asFile.absolutePath,
        providers.gradleProperty("functionAuditQuackUrl").getOrElse("jdbc:quack://127.0.0.1:9494"))
}

val verifyFunctionAuditIsolation by tasks.registering {
    group = "verification"
    description = "Verify audit libraries/classes cannot leak into the Doris connector package"
    dependsOn(pluginZip)
    doLast {
        val auditJars = configurations.runtimeClasspath.get().files.filter { it.name.startsWith("brikk-sql-") }
        check(auditJars.isEmpty()) { "audit-only libraries leaked into the runtime classpath: $auditJars" }
        val kotlinJars = configurations.runtimeClasspath.get().files.filter { it.name.startsWith("kotlin-stdlib-") }
        check(kotlinJars.map { it.name } == listOf("kotlin-stdlib-${libs.versions.kotlin.get()}.jar")) {
            "audit runtime must not change the connector's Kotlin runtime: $kotlinJars"
        }
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            check(jar.entries().asSequence().none { it.name.startsWith("dev/brikk/duckbridge/doris/audit/") }) {
                "audit classes must not be bundled in the connector JAR"
            }
        }
        ZipFile(pluginZip.get().archiveFile.get().asFile).use { zip ->
            check(zip.entries().asSequence().none { it.name.substringAfterLast('/').startsWith("brikk-sql-") }) {
                "audit-only libraries must not be bundled in the connector ZIP"
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyFunctionAuditIsolation) }
