package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class TestDuckBridgeConnectorProviderLoads {

    @Test
    fun serviceLoaderDiscoversTheProvider() {
        val providers = ServiceLoader.load(ConnectorProvider::class.java)
            .toList()

        assertThat(providers)
            .withFailMessage(
                "ServiceLoader found no ConnectorProvider — check " +
                    "resources/META-INF/services/org.apache.doris.connector.spi.ConnectorProvider",
            )
            .anyMatch { it is DuckBridgeConnectorProvider }
    }

    @Test
    fun discoveredProviderReportsDuckbridgeType() {
        val provider = ServiceLoader.load(ConnectorProvider::class.java)
            .first { it is DuckBridgeConnectorProvider }

        assertThat(provider.type).isEqualTo("duckbridge")
        assertThat(provider.type).isEqualTo(DuckBridgeConnectorProvider.TYPE)
    }
}
