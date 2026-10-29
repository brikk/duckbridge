*Doris JNI parameter-map conversion corrupts supplementary Unicode in pushed SQL*

Found while testing Doris master `082736138b7f0e767f966cbb1434bc9ba85f29ec`
(connector API 24 / JNI API 6, Linux x86-64 ASAN BE, OpenJDK 17.0.2).

*Issue:* C++ scanner parameters contain ordinary UTF-8, but
`Jni::Util::convert_to_java_map()` passes their keys/values through
`String<Local>::new_string()` → `JNIEnv::NewStringUTF()`. JNI's `NewStringUTF`
expects *modified UTF-8*, not ordinary UTF-8. A supplementary character such as
😀 (`F0 9F 98 80`) is therefore not decoded correctly. This affects `query_sql`
before the JDBC driver executes it; it is not specific to OVERLAY.

*Observed SQL reproduction:* on our JDBC-backed external table, row 2 contains
`source = 'a😀bc'`. With the client connected using utf8mb4:

```sql
SELECT HEX('a😀bc'), CHARACTER_LENGTH('a😀bc');
-- 61F09F98806263, 4

SELECT id, HEX(source)
FROM duckbridge_test.sales.overlay_inputs WHERE id = 2;
-- 2, 61F09F98806263

EXPLAIN VERBOSE
SELECT id FROM duckbridge_test.sales.overlay_inputs WHERE source = 'a😀bc';
-- QUERY contains the correct: WHERE ("source" = 'a😀bc')

SELECT id FROM duckbridge_test.sales.overlay_inputs WHERE source = 'a😀bc';
-- Expected: 2
-- Actual: JdbcJniScanner open failed ... unterminated string literal
-- The remote diagnostic contains corrupted/truncated text in place of 😀.
```

This was observed with our SPI connector using the native JDBC/JNI scanner,
Quack JDBC 0.10.0 and DuckDB 2.0.0. The native JNI conversion code is unmodified;
our existing BE-side connector patch is a Java DuckDB type handler. Direct
Quack JDBC preserves the same Unicode predicates. I have not independently
verified another JDBC driver/catalog.

*Minimal reproduction without Doris or any database:* attached
`jni_utf8_repro.cpp` calls the same JNI API with UTF-8 bytes `61 F0 9F 98 80 62 63`.
On OpenJDK 17.0.2 and 17.0.19:

```text
Expected UTF-16: 0061 D83D DE00 0062 0063
NewStringUTF(ordinary UTF-8): 0061 00F0 009F 0098
BUG REPRODUCED
```

The precise corruption is JVM-dependent because the input is not valid JNI
modified UTF-8.

*Proposed fix attached:* one-file patch to `be/src/util/jni-util.h`. Add a
length-aware standard-UTF-8 helper using the existing simdutf dependency to
validate/decode UTF-16, then call `NewString()`. Use it for parameter-map keys
and values; leave existing modified-UTF-8 callers unchanged. This also preserves
embedded NUL and rejects malformed standard UTF-8 rather than replacing bytes.

Validation so far: patch applies to the reported pin; the actual patched header
passes Clang syntax/template-instantiation checks with that pin's BE flags.
Standalone decoder/JVM checks preserve six valid cases and reject four invalid
encodings. *Not yet a full BE build, Doris regression test, or patched-cluster
run*—please review and run those upstream. No native fix has been deployed here.
