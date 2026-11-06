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

import java.util.Locale

/**
 * S3 settings for DuckDB's httpfs extension on the Arrow read path, read from the standard `s3.*`
 * catalog keys. When [endpoint] is absent DuckDB uses AWS-default endpoint resolution.
 */
@JvmRecord
data class DuckDbS3Config(
    val endpoint: String?,
    val region: String?,
    val accessKey: String?,
    val secretKey: String?,
    val pathStyleAccess: Boolean,
    val useSsl: Boolean,
) {
    /**
     * Render a DuckDB `CREATE SECRET IF NOT EXISTS duckbridge_s3 (TYPE S3, ...)` statement.
     * `IF NOT EXISTS` (NOT `OR REPLACE`) is load-bearing on the Quack path, where the secret is
     * server-instance-scoped and shared across concurrent sessions: `OR REPLACE` under concurrency
     * races with in-flight scans, while `IF NOT EXISTS` on an existing secret performs no catalog
     * write. First-contact creates can still conflict and are retried by [DuckDbCatalogWriteRetry].
     */
    fun renderCreateSecretSql(): String =
        buildString {
            append("CREATE SECRET IF NOT EXISTS duckbridge_s3 (TYPE S3")
            endpoint?.let { e -> append(", ENDPOINT '").append(stripScheme(e).replace("'", "''")).append("'") }
            region?.let { r -> append(", REGION '").append(r.replace("'", "''")).append("'") }
            accessKey?.let { k -> append(", KEY_ID '").append(k.replace("'", "''")).append("'") }
            secretKey?.let { s -> append(", SECRET '").append(s.replace("'", "''")).append("'") }
            append(", URL_STYLE '").append(if (pathStyleAccess) "path" else "vhost").append("'")
            append(", USE_SSL ").append(useSsl)
            append(")")
        }

    companion object {
        fun fromCatalogConfig(config: Map<String, String>): DuckDbS3Config {
            val endpoint = trimmed(config["s3.endpoint"])
            val useSsl = endpoint?.let { e -> !e.lowercase(Locale.ROOT).startsWith("http://") } ?: true
            return DuckDbS3Config(
                endpoint,
                trimmed(config["s3.region"]),
                trimmed(config["s3.aws-access-key"]),
                trimmed(config["s3.aws-secret-key"]),
                config.getOrDefault("s3.path-style-access", "false").toBoolean(),
                useSsl,
            )
        }

        private fun trimmed(value: String?): String? = value?.trim()?.ifEmpty { null }

        private fun stripScheme(url: String): String {
            // DuckDB's S3 ENDPOINT field expects host:port, not a full URL.
            val idx = url.indexOf("://")
            return if (idx < 0) url else url.substring(idx + 3)
        }
    }
}
