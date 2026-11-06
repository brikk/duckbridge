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

import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import java.nio.file.Files

object DuckBridgeQueryRunner {
    const val CATALOG = "duckbridge"
    const val SCHEMA = "test_schema"

    /**
     * DuckDB creates the file on first connect; delete the empty placeholder so it can initialise
     * a fresh database.
     */
    fun freshDatabaseUrl(): String {
        val path = Files.createTempFile("duckbridge-", ".db")
        Files.delete(path)
        return "jdbc:duckdb:$path"
    }

    fun create(connectionUrl: String): QueryRunner = create(connectionUrl, emptyMap())

    fun bundledParityIsUnsigned(): Boolean =
        TrinoParityExtensionResolver.resolveBundledExtensionPath()?.let(TrinoParityExtensionResolver::isUnsigned) == true

    fun duckJdbcProperties(): java.util.Properties =
        java.util.Properties().also { p ->
            if (bundledParityIsUnsigned()) {
                p.setProperty("allow_unsigned_extensions", "true")
            }
        }

    fun create(connectionUrl: String, extraProperties: Map<String, String>): QueryRunner {
        val session =
            testSessionBuilder()
                .setCatalog(CATALOG)
                .setSchema(SCHEMA)
                .build()
        val queryRunner = DistributedQueryRunner.builder(session).build()
        try {
            queryRunner.installPlugin(DuckBridgePlugin())
            queryRunner.createCatalog(
                CATALOG,
                "duckbridge",
                buildMap {
                    put("connection-url", connectionUrl)
                    // Only local unsigned builds need the opt-in; release bundles retain signature verification.
                    if (bundledParityIsUnsigned()) {
                        put("duckbridge.allow-unsigned-extensions", "true")
                    }
                    putAll(extraProperties)
                },
            )
            return queryRunner
        } catch (e: Throwable) {
            queryRunner.close()
            throw e
        }
    }
}
