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

import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.Call
import java.util.Optional

/**
 * A single base-jdbc [ConnectorExpressionRule] that hands a whole [Call] conjunct to
 * [DuckBridgeExpressionTranslator], with both trust axes read from the session's
 * [DuckBridgeStringPushdownMode]. The translator inlines only constants it can render losslessly, so
 * the resulting [ParameterizedExpression] intentionally has no bound parameters.
 */
class DuckBridgeParityExpressionRule : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(
        call: Call,
        captures: Captures,
        context: ConnectorExpressionRule.RewriteContext<ParameterizedExpression>,
    ): Optional<ParameterizedExpression> {
        val mode = DuckBridgeSessionProperties.getStringPushdownMode(context.session)
        val sql =
            DuckBridgeExpressionTranslator.translate(
                call,
                context.assignments,
                context.session,
                mode.aliasAvailable,
                mode.allowsFullStringComparison,
            ) ?: return Optional.empty()
        return Optional.of(ParameterizedExpression(sql, emptyList<QueryParameter>()))
    }

    private companion object {
        private val PATTERN: Pattern<Call> = ConnectorExpressionPatterns.call()
    }
}
