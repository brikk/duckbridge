package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.PredicatePushdownController
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult
import io.trino.plugin.jdbc.PredicatePushdownController.FULL_PUSHDOWN
import io.trino.spi.predicate.Domain
import io.trino.spi.predicate.Range
import io.trino.spi.predicate.ValueSet
import io.trino.spi.type.DoubleType

/** Trino non-ALL floating ranges exclude NaN, even when their upper endpoint is unbounded. */
internal object DuckBridgeFloatingPredicatePushdown {
    fun mapping(standard: ColumnMapping): ColumnMapping =
        ColumnMapping.mapping(standard.type, standard.readFunction, standard.writeFunction, PUSHDOWN)

    val PUSHDOWN: PredicatePushdownController = PredicatePushdownController { session, domain ->
        val result = FULL_PUSHDOWN.apply(session, domain)
        val pushed = result.pushedDown
        if (pushed.values.isAll || pushed.values.isNone) result
        else {
            val infinity: Any = if (pushed.type is DoubleType) Double.POSITIVE_INFINITY
                else java.lang.Float.floatToRawIntBits(Float.POSITIVE_INFINITY).toLong()
            val nonNan = Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(pushed.type, infinity)), true)
            DomainPushdownResult(pushed.intersect(nonNan), result.remainingFilter)
        }
    }
}
