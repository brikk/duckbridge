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
// Doris sibling of :trino-duckbridge — packages stay separated per engine
// (dev.brikk.duckbridge.doris.* vs dev.brikk.duckbridge.trino.*). Targets Doris's
// fe-connector catalog SPI (branch-catalog-spi); SPI jars resolve from the project-local
// doris-duckbridge/doris-m2/ repo, bootstrapped by tools/doris-baseline.sh --install-spi-jars.
include(":doris-duckbridge")
