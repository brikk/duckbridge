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

import com.google.common.collect.ImmutableList
import com.google.inject.Inject
import io.trino.plugin.base.session.SessionPropertiesProvider
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.session.PropertyMetadata
import io.trino.spi.session.PropertyMetadata.booleanProperty
import io.trino.spi.session.PropertyMetadata.enumProperty

/** Session properties consulted by the pushdown paths. */
class DuckBridgeSessionProperties
    @Inject
    constructor(config: DuckBridgeConfig) : SessionPropertiesProvider {
        private val sessionProperties: List<PropertyMetadata<*>> =
            ImmutableList.of(
                booleanProperty(
                    PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE,
                    "Enable pushdown of date/time function predicates over TIMESTAMP WITH TIME ZONE " +
                        "columns to DuckDB. On by default. Requires a successful SET TimeZone on the " +
                        "session connection (automatic for all named IANA zones and integer-hour offsets; " +
                        "fractional bare offsets like '+05:30' degrade to Trino-side evaluation). Set to " +
                        "false to keep these predicates above the scan.",
                    true,
                    false,
                ),
                enumProperty(
                    STRING_PUSHDOWN_MODE,
                    "String predicate pushdown mode (NULL_ONLY, GUARDED, BINARY, FULL, PARITY) — per-query " +
                        "override of duckbridge.string-pushdown.mode.",
                    DuckBridgeStringPushdownMode::class.java,
                    config.stringPushdownMode,
                    false,
                ),
            )

        override fun getSessionProperties(): List<PropertyMetadata<*>> = sessionProperties

        companion object {
            const val PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE: String = "pushdown_timestamp_with_timezone"
            const val STRING_PUSHDOWN_MODE: String = "string_pushdown_mode"

            /** A null session reads as the safest mode, NULL_ONLY. */
            fun getStringPushdownMode(session: ConnectorSession?): DuckBridgeStringPushdownMode =
                if (session == null) {
                    DuckBridgeStringPushdownMode.NULL_ONLY
                } else {
                    session.getProperty(STRING_PUSHDOWN_MODE, DuckBridgeStringPushdownMode::class.java)
                }

            /** A null session reads as off, so TIMESTAMP WITH TIME ZONE predicates are not pushed. */
            fun isPushdownTimestampWithTimeZone(session: ConnectorSession?): Boolean =
                session?.getProperty(PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE, Boolean::class.javaObjectType) == true
        }
    }
