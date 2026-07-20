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

import io.airlift.log.Logger
import io.trino.spi.Page
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException
import io.trino.spi.connector.ConnectorPageSource
import io.trino.spi.connector.SourcePage
import java.io.IOException

/**
 * T2 Arrow page source over a [DuckBridgeExecutor.ExecutionContext] (the QUACK engine). The executor
 * has already run the query — server-side via `quack_query_by_name` — and exposes the result as an
 * `ArrowReader`; this source drains it batch-by-batch and decodes each to a Trino [Page].
 *
 * Contrast with [DuckBridgeArrowPageSource] (DUCKDB_LOCAL), which holds a live `PreparedStatement`
 * and runs it lazily on first read. Here the query is already executing, so we only iterate.
 */
class DuckBridgeExecutorPageSource(
    private val context: DuckBridgeExecutor.ExecutionContext,
    private val converter: DuckBridgeArrowToPageConverter,
    private val projectedColumnCount: Int,
) : ConnectorPageSource {
    private val reader = context.arrowReader()
    private var finished = false
    private var completedBytes = 0L

    @Deprecated("SPI-deprecated but still abstract in 483", ReplaceWith("getMetrics()"))
    override fun getCompletedBytes(): Long = completedBytes

    @Deprecated("SPI-deprecated but still abstract in 483", ReplaceWith("getMetrics()"))
    override fun getReadTimeNanos(): Long = 0

    override fun isFinished(): Boolean = finished

    override fun getNextSourcePage(): SourcePage? {
        if (finished) {
            return null
        }
        try {
            if (!reader.loadNextBatch()) {
                finished = true
                return null
            }
            val root = reader.vectorSchemaRoot
            val page: Page =
                if (projectedColumnCount == 0) {
                    // Empty projection (e.g. count(*)): base-jdbc emits a single dummy column so
                    // positions flow; only the row count matters, not the dummy values.
                    Page(root.rowCount)
                } else {
                    converter.convert(root)
                }
            completedBytes += page.sizeInBytes
            return SourcePage.create(page)
        } catch (e: IOException) {
            throw TrinoException(GENERIC_INTERNAL_ERROR, "DuckBridge Quack Arrow read failed", e)
        }
    }

    override fun getMemoryUsage(): Long = context.memoryUsage()

    override fun close() {
        try {
            context.close()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            log.warn(t, "Error closing DuckBridge Quack Arrow page source")
        }
    }

    private companion object {
        private val log: Logger = Logger.get(DuckBridgeExecutorPageSource::class.java)
    }
}
