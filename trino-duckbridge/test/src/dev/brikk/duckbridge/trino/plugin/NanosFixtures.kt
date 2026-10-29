package dev.brikk.duckbridge.trino.plugin

internal object NanosFixtures {
    data class Row(val id: Int, val timestamp: String?, val clock: String?, val zoned: String?)
    val rows = listOf(
        Row(0, "1969-12-31 23:59:59.999999999", "23:59:59.999999999", "1969-12-31 23:59:59.999999999"),
        Row(1, "1970-01-01 00:00:00.000000001", "00:00:00.000000001", "1970-01-01 00:00:00.000000001"),
        Row(2, "1969-12-31 23:59:59.999998999", "12:34:56.123456789", "1969-12-31 23:59:59.999998999"),
        Row(3, "1969-12-31 23:59:59.999999001", "00:00:00.000000999", "1969-12-31 23:59:59.999999001"),
        Row(4, "2024-03-10 02:30:00.123456789", "02:30:00.123456789", "2024-03-10 10:30:00.123456789"),
        Row(5, "2024-11-03 01:30:00.123456789", "01:30:00.123456789", "2024-11-03 08:30:00.123456789"),
        Row(6, "2024-11-03 01:30:00.123456789", "01:30:00.123456789", "2024-11-03 09:30:00.123456789"),
        Row(7, "2262-04-11 23:47:16.854775806", "23:47:16.854775806", "2262-04-11 23:47:16.854775806"),
        Row(8, "1677-09-21 00:12:43.145224194", "00:12:43.145224194", "1677-09-21 00:12:43.145224194"),
        Row(9, null, null, null),
    )

    fun values(duck: Boolean): String = rows.joinToString(",") { row ->
        val ts = temporal(row.timestamp, if (duck) "TIMESTAMP_NS" else "timestamp(9)")
        val time = temporal(row.clock, if (duck) "TIME_NS" else "time(9)")
        val tz = temporal(row.zoned?.plus(if (duck) "+00" else " UTC"), if (duck) "TIMESTAMPTZ_NS" else "timestamp(9) with time zone")
        "(${row.id},$ts,$time,$tz)"
    }

    private fun temporal(value: String?, type: String): String =
        if (value == null) "CAST(NULL AS $type)" else "CAST('$value' AS $type)"
}
