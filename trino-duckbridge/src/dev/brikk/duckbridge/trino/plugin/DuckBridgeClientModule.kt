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

import com.google.inject.Binder
import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder.newSetBinder
import com.google.inject.multibindings.OptionalBinder.newOptionalBinder
import io.airlift.configuration.AbstractConfigurationAwareModule
import io.airlift.configuration.ConfigBinder.configBinder
import io.opentelemetry.api.OpenTelemetry
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.base.session.SessionPropertiesProvider
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.DriverConnectionFactory
import io.trino.plugin.jdbc.ForBaseJdbc
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcStatisticsConfig
import io.trino.plugin.jdbc.JdbcPageSourceProvider
import io.trino.plugin.jdbc.credential.CredentialProvider
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.plugin.jdbc.ptf.Query
import io.trino.spi.connector.ConnectorPageSourceProvider
import io.trino.spi.function.table.ConnectorTableFunction
import com.gizmodata.quack.jdbc.sql.QuackDriver
import org.duckdb.DuckDBDriver
import java.sql.Driver
import java.util.Properties

/** Guice wiring for the DuckBridge connector. */
class DuckBridgeClientModule(
    /** Test seam for end-to-end pushed-vs-local row-set parity; production always uses the default. */
    private val expressionPushdownEnabled: Boolean = true,
) : AbstractConfigurationAwareModule() {
    override fun setup(binder: Binder) {
        binder.bind(JdbcClient::class.java)
            .annotatedWith(ForBaseJdbc::class.java)
            .to(DuckBridgeClient::class.java)
            .`in`(Scopes.SINGLETON)
        configBinder(binder).bindConfig(JdbcStatisticsConfig::class.java)
        configBinder(binder).bindConfig(DuckBridgeConfig::class.java)
        binder.bind(DuckBridgeParity::class.java).`in`(Scopes.SINGLETON)
        binder.bind(DuckBridgeScanExtensions::class.java).`in`(Scopes.SINGLETON)
        binder.bind(DuckBridgeExecutorFactory::class.java).`in`(Scopes.SINGLETON)
        binder.bind(JdbcPageSourceProvider::class.java).`in`(Scopes.SINGLETON)
        newOptionalBinder(binder, ConnectorPageSourceProvider::class.java)
            .setBinding()
            .to(DuckBridgePageSourceProvider::class.java)
            .`in`(Scopes.SINGLETON)
        newSetBinder(binder, SessionPropertiesProvider::class.java)
            .addBinding()
            .to(DuckBridgeSessionProperties::class.java)
            .`in`(Scopes.SINGLETON)
        val tableFunctions = newSetBinder(binder, ConnectorTableFunction::class.java)
        tableFunctions.addBinding().toProvider(Query::class.java).`in`(Scopes.SINGLETON)
        // Always registered so a clear "extension not enabled" error fires at analyze time.
        tableFunctions.addBinding().toProvider(LanceScanFunctionProvider::class.java).`in`(Scopes.SINGLETON)
        tableFunctions.addBinding().toProvider(VortexScanFunctionProvider::class.java).`in`(Scopes.SINGLETON)
        tableFunctions.addBinding().toProvider(LanceVectorSearchFunctionProvider::class.java).`in`(Scopes.SINGLETON)
        tableFunctions.addBinding().toProvider(LanceFtsFunctionProvider::class.java).`in`(Scopes.SINGLETON)
        tableFunctions.addBinding().toProvider(LanceHybridSearchFunctionProvider::class.java).`in`(Scopes.SINGLETON)
    }

    /**
     * Deliberately omits base-jdbc's `addStandardRules`: the single [DuckBridgeParityExpressionRule]
     * covers those shapes too, and resolves the string-pushdown mode per session.
     */
    @Provides
    @Singleton
    fun connectorExpressionRewriter(): ConnectorExpressionRewriter<ParameterizedExpression> =
        JdbcConnectorExpressionRewriterBuilder.newBuilder().let { builder ->
            if (expressionPushdownEnabled) {
                builder.add(DuckBridgeParityExpressionRule())
            }
            builder.build()
        }

    @Provides
    @Singleton
    fun transport(config: BaseJdbcConfig): DuckBridgeTransport =
        DuckBridgeTransport.fromConnectionUrl(config.connectionUrl)

    @Provides
    @Singleton
    @ForBaseJdbc
    @Suppress("LongParameterList")
    fun connectionFactory(
        config: BaseJdbcConfig,
        duckBridgeConfig: DuckBridgeConfig,
        transport: DuckBridgeTransport,
        credentialProvider: CredentialProvider,
        openTelemetry: OpenTelemetry,
        parity: DuckBridgeParity,
        scanExtensions: DuckBridgeScanExtensions,
    ): ConnectionFactory {
        val driver: Driver
        val connectionProperties = Properties()
        when (transport) {
            DuckBridgeTransport.EMBEDDED -> {
                driver = DuckDBDriver()
                if (duckBridgeConfig.isAllowUnsignedExtensions) {
                    connectionProperties.setProperty("allow_unsigned_extensions", "true")
                }
            }
            DuckBridgeTransport.QUACK -> {
                driver = QuackDriver()
                // Credentials and TLS come from config so they stay out of the copy-pasteable URL.
                duckBridgeConfig.quackToken?.let { connectionProperties.setProperty("token", it) }
                duckBridgeConfig.quackTokenEnv?.let { connectionProperties.setProperty("tokenEnv", it) }
                duckBridgeConfig.quackTokenFile?.let { connectionProperties.setProperty("tokenFile", it) }
                if (duckBridgeConfig.isQuackTls) {
                    connectionProperties.setProperty("tls", "true")
                }
            }
        }
        val base =
            DriverConnectionFactory.builder(driver, config.connectionUrl, credentialProvider)
                .setConnectionProperties(connectionProperties)
                .setOpenTelemetry(openTelemetry)
                .build()
        return DuckBridgeExtensionConnectionFactory(base, parity, scanExtensions)
    }
}
