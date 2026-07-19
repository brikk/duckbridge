pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Resolves and downloads JDK toolchains on demand (jvmToolchain(25)) from the
    // Foojay Disco API, so builds don't depend on a matching local JDK install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "duckbridge"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":trino-duckbridge")
// Room for a future :doris-duckbridge sibling — packages stay separated per engine.
