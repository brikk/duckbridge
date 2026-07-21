import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    source.setFrom("src", "test/src")
}

// PROJECT-LOCAL Doris artifact repository — the self-contained bootstrap.
//
// The Doris fe-connector-api / fe-connector-spi / fe-thrift artifacts are built from OUR pinned
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
    // Brikk's patched quack-jdbc (dev.brikk.duckdb:quack-jdbc) with the LIST/ARRAY element-type fix
    // (gizmodata/quack-jdbc PR), published as a snapshot until upstream cuts a release.
    maven {
        name = "centralSnapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}

// SPI jars are 1.2-SNAPSHOT, built from our pinned baseline into doris-m2/ by the bootstrap.
val dorisVersion = "1.2-SNAPSHOT"

// Actionable pre-bootstrap failure: if the SPI jars aren't in doris-m2/ yet, fail with the exact
// command to run rather than a cryptic "could not resolve org.apache.doris:...". Checks for the
// api jar (the anchor artifact) at its Maven layout path under doris-m2/.
val dorisSpiAnchorJar = dorisLocalRepo.file(
    "org/apache/doris/fe-connector-api/$dorisVersion/fe-connector-api-$dorisVersion.jar",
)
val bootstrapHint =
    "Doris SPI jars are not bootstrapped. Run:\n\n" +
        "    tools/doris-baseline.sh --install-spi-jars\n\n" +
        "This builds fe-connector-api / fe-connector-spi / fe-thrift from the pinned Doris " +
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
    // FE supplies these via the parent classloader at runtime — compile-only, so the plugin jar
    // ships no second copy of the SPI/thrift classes (a duplicate would LinkageError across the
    // SPI boundary).
    compileOnly("org.apache.doris:fe-connector-api:$dorisVersion")
    compileOnly("org.apache.doris:fe-connector-spi:$dorisVersion")
    compileOnly("org.apache.doris:fe-thrift:$dorisVersion")

    // quack-jdbc: gizmo's pure-JVM DuckDB/Quack driver (jdbc:quack://...). The FE resolves
    // schemas/tables/columns through it at plan time; the BE loads it in its own JVM to run the
    // scan. Version from the repo-wide catalog. Bundled in the plugin zip (the FE classloader has
    // no such driver of its own).
    implementation(libs.quack.jdbc)

    // Tests instantiate the provider / connector, so the SPI types are needed at test time too.
    testImplementation("org.apache.doris:fe-connector-api:$dorisVersion")
    testImplementation("org.apache.doris:fe-connector-spi:$dorisVersion")
    // junit / assertj / kotlin-test come from buildlogic.kotlin.common.

    // P4 metadata probe + the over-Quack integration tests spin up a real DuckDB/Quack server
    // (testcontainers) and talk to it through quack-jdbc — the same driver the FE uses at plan
    // time. quack-jdbc is `implementation` above (bundled in the plugin); tests need it on the
    // test classpath explicitly too.
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.quack.jdbc)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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
        exclude("fe-connector-api-*.jar")
        exclude("fe-connector-spi-*.jar")
        exclude("fe-thrift-*.jar")
    }
}

tasks.named("assemble") {
    dependsOn(pluginZip)
}
