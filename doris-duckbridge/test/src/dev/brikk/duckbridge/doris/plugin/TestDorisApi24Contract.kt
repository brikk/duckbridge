package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorValidationContext
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.thrift.TPaimonFileDesc
import org.apache.doris.thrift.TReportExecStatusParams
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Properties

class TestDorisApi24Contract {
    private val properties = mapOf("jdbc_url" to "jdbc:quack://localhost:9494")

    @Test
    fun compileClosureIsApi24WithMatchingWireFields() {
        val resource = "META-INF/doris/connector-plugin-api-version.properties"
        val version = Properties().apply {
            checkNotNull(TestDorisApi24Contract::class.java.classLoader.getResourceAsStream(resource)).use { load(it) }
        }
        assertThat(version.getProperty("api.version")).isEqualTo("24.0")
        assertThat(TReportExecStatusParams._Fields.PAIMON_COMMIT_MESSAGES.thriftFieldId.toInt()).isEqualTo(34)
        assertThat(TPaimonFileDesc._Fields.ORIGINAL_FILE_PATH.thriftFieldId.toInt()).isEqualTo(20)
    }

    @Test
    fun providerDeclaresTheConsumedDriverUrlWithoutInventingAliases() {
        val surface = DuckBridgeConnectorProvider().securitySurface()
        assertThat(surface.jdbcDriverUrlKeys()).containsExactly("driver_url")
        assertThat(surface.jdbcDriverUrlFlavorKey()).isNull()
        // Like upstream JDBC, this is a driver policy declaration, not a JDBC endpoint SSRF claim.
        assertThat(surface.ssrfEndpointKeys()).isEmpty()
    }

    @Test
    fun statementValidationRejectsDriverTraversalAndAlterCandidates() {
        val provider = DuckBridgeConnectorProvider()
        for (url in listOf("../evil.jar", "file:///opt/drivers/%2e%2e/evil.jar")) {
            assertThatThrownBy { provider.validateProperties(properties + ("driver_url" to url)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("traversal")
            assertThatThrownBy {
                provider.validatePropertiesForUpdate(properties, mapOf("driver_url" to url))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        provider.validateProperties(properties + ("driver_url" to "file:///opt/drivers/quack-jdbc.jar"))
        provider.validateProperties(properties)
    }

    @Test
    fun createDelegatesOperatorPolicyAndPropagatesRejection() {
        val driverUrl = "file:///opt/drivers/quack-jdbc.jar"
        val connector = connector(properties + ("driver_url" to driverUrl))
        var checkedUrl: String? = null
        val validation = proxy(ConnectorValidationContext::class.java) { method, args ->
            check(method == "validateAndResolveDriverPath") { "Unexpected validation call: $method" }
            checkedUrl = args?.single() as String
            throw IllegalArgumentException("operator policy rejected driver")
        }
        assertThatThrownBy { connector.preCreateValidation(validation) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("operator policy")
        assertThat(checkedUrl).isEqualTo(driverUrl)
    }

    @Test
    fun reconstructionDoesNotApplyNewStatementTimeDriverRules() {
        val connector = connector(properties + ("driver_url" to "historical/path.jar"))
        assertThat(connector.config().driverUrl).isEqualTo("historical/path.jar")
    }

    @Test
    fun optionalDriverDoesNotTriggerValidationInfrastructure() {
        val validation = proxy(ConnectorValidationContext::class.java) { method, _ ->
            error("Unexpected validation call without a driver URL: $method")
        }
        connector(properties).preCreateValidation(validation)
    }

    @Test
    fun newSpiDefaultsKeepReadOnlySynchronousOneRangeScans() {
        val connector = connector(properties)
        val handle = DuckBridgeTableHandle("memory", "sales", "customers")
        val scan = connector.getScanPlanProvider()
        assertThat(connector.capabilities).isEmpty()
        assertThat(connector.writePlanProvider).isNull()
        assertThat(connector.eventSource).isNull()
        assertThat(connector.getMetadata(null).supportsNativePartitionPlanning(null, handle)).isFalse()
        assertThat(connector.getMetadata(null).getSqlCacheVersion(null, handle)).isEmpty()
        assertThat(scan.supportsBatchScan(null, handle)).isFalse()
        assertThat(scan.supportsTableSample()).isFalse()
        val request = ConnectorScanRequest.builder(handle, emptyList()).build()
        val ranges = scan.planScan(null, request)
        assertThat(ranges).hasSize(1)
        assertThat(ranges.single().tableFormatType).isEqualTo("jdbc")
        assertThat(ranges.single().properties["table_type"]).isEqualTo("DUCKDB")
        assertThatThrownBy { scan.streamSplits(null, request) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    private fun connector(properties: Map<String, String>): DuckBridgeConnector {
        val context = proxy(ConnectorContext::class.java) { method, _ ->
            check(method == "getCatalogId") { "Unexpected context call: $method" }
            42L
        }
        return DuckBridgeConnector(DuckBridgeConnectorConfig.from(properties), context)
    }

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args)
        })
}
