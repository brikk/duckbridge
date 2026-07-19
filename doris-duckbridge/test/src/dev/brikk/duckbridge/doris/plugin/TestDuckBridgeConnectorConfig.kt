package dev.brikk.duckbridge.doris.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Config-holder tests. Honest scope: this covers the property parsing the scaffold actually
 * implements — NOT metadata/scan behavior (which fails loud pending probes P1–P6).
 */
class TestDuckBridgeConnectorConfig {

    @Test
    fun parsesRequiredUrlAndDefaultsDriver() {
        val config = DuckBridgeConnectorConfig.from(
            mapOf(DuckBridgeConnectorConfig.PROP_JDBC_URL to "jdbc:quack://duck:4200"),
        )

        assertThat(config.jdbcUrl).isEqualTo("jdbc:quack://duck:4200")
        assertThat(config.driverClass).isEqualTo(DuckBridgeConnectorConfig.DEFAULT_DRIVER_CLASS)
        assertThat(config.driverUrl).isNull()
        assertThat(config.user).isNull()
        assertThat(config.password).isNull()
    }

    @Test
    fun overridesOptionalProperties() {
        val config = DuckBridgeConnectorConfig.from(
            mapOf(
                DuckBridgeConnectorConfig.PROP_JDBC_URL to "jdbc:quack://duck:4200",
                DuckBridgeConnectorConfig.PROP_DRIVER_CLASS to "com.example.Driver",
                DuckBridgeConnectorConfig.PROP_DRIVER_URL to "https://jars/quack.jar",
                DuckBridgeConnectorConfig.PROP_USER to "analyst",
                DuckBridgeConnectorConfig.PROP_PASSWORD to "token",
            ),
        )

        assertThat(config.driverClass).isEqualTo("com.example.Driver")
        assertThat(config.driverUrl).isEqualTo("https://jars/quack.jar")
        assertThat(config.user).isEqualTo("analyst")
        assertThat(config.password).isEqualTo("token")
    }

    @Test
    fun failsLoudWhenUrlMissing() {
        assertThatThrownBy { DuckBridgeConnectorConfig.from(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(DuckBridgeConnectorConfig.PROP_JDBC_URL)
    }

    @Test
    fun blankUrlIsTreatedAsMissing() {
        assertThatThrownBy {
            DuckBridgeConnectorConfig.from(mapOf(DuckBridgeConnectorConfig.PROP_JDBC_URL to "   "))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
