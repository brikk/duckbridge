# Doris JNI UTF-8 report attachment

Send [`slack.md`](slack.md), with [`proposed-fix.patch`](proposed-fix.patch) and
[`jni_utf8_repro.cpp`](jni_utf8_repro.cpp) attached. This is a report and patch
proposal, **not** a change to DuckBridge's required Doris patch set or runtime.

## Root cause / exact source path

Verified at Apache Doris `082736138b7f0e767f966cbb1434bc9ba85f29ec`:

1. `be/src/exec/scan/jdbc_scanner.cpp:69` sets `params["query_sql"] = _query_string`.
2. `be/src/format/jni/jni_reader.cpp:255` creates the Java scanner with those parameters.
3. `be/src/util/jni_plugin_registry.cpp:118` converts the C++ parameter map.
4. `be/src/util/jni-util.h:1172–1175` passes each key/value's `c_str()` to
   `String<Local>::new_string`.
5. `be/src/util/jni-util.h:796` calls `env->NewStringUTF(utf_chars)`.

The input is standard UTF-8. The JNI API requires modified UTF-8: U+1F600 must
be represented as the two UTF-16 surrogate units encoded separately, not its
standard four-byte UTF-8 sequence. See Oracle's
[JNI string API specification](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#newstringutf).

The independent reproduction intentionally exercises the invalid API/input
combination. Do not assume identical output on every JVM or enable a mode that
aborts immediately on malformed modified UTF-8 when comparing the example output.

## Run the database-independent reproduction (Linux + JDK)

From this attachment directory, with `JAVA_HOME` pointing to a JDK:

```bash
c++ -std=c++17 -Wall -Wextra -Werror \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  jni_utf8_repro.cpp \
  -L"$JAVA_HOME/lib/server" -Wl,-rpath,"$JAVA_HOME/lib/server" \
  -ljvm -o jni_utf8_repro
./jni_utf8_repro
```

Expected output on the two tested JDKs:

```text
Expected UTF-16: 0061 D83D DE00 0062 0063
NewStringUTF(ordinary UTF-8): 0061 00F0 009F 0098
BUG REPRODUCED
```

No JDBC driver, database, connector plugin or Doris build is required. The program
also checks that constructing the expected UTF-16 with `NewString` preserves it.
Exit 0 means the mismatch was reproduced; exit 3 means that JVM happened to return
the expected result despite the invalid input contract.

### Exercise the proposed decoder

With Doris's existing simdutf installation available, add its include/library
paths and `-DTEST_SIMDUTF_FIX`. For example:

```bash
TP="${DORIS_THIRDPARTY}/installed"
c++ -std=c++17 -Wall -Wextra -Werror -DTEST_SIMDUTF_FIX \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$TP/include" \
  jni_utf8_repro.cpp \
  -L"$JAVA_HOME/lib/server" -Wl,-rpath,"$JAVA_HOME/lib/server" \
  -ljvm "$TP/lib64/libsimdutf.a" -o jni_utf8_repro_simdutf
./jni_utf8_repro_simdutf
```

This additionally checks empty/ASCII/BMP/supplementary strings, embedded NUL and
U+10FFFF, plus rejection of truncated UTF-8, overlong NUL, an encoded unpaired
surrogate and an invalid leading byte. Expected additional line:

```text
Proposed decoder: 6 valid cases preserved, 4 invalid encodings rejected
```

These are standalone checks of the decoder/NewString sequence, **not** a claim
that the full Doris helper or map was runtime-tested in a rebuilt BE.

## Proposed patch scope / upstream validation

```bash
git apply --check /path/to/proposed-fix.patch
git apply /path/to/proposed-fix.patch
```

The patch adds `String<Ref>::new_string_utf8` and changes only parameter-map
keys/values to use it. It uses explicit string length, validates input with
simdutf, checks the allocation bound, preserves embedded NUL, creates a Java
UTF-16 string, and supports local/global references. It deliberately leaves the
older `new_string(const char*)` helper untouched: changing every caller's input
contract would need a broader audit. Java→C++ modified-UTF-8 conversion is also
outside this narrowly scoped proposal.

Checked locally:

- Clean `git apply --check` against the reported pin.
- Clang `-fsyntax-only`, with the pin's actual BE include/define/warning flags,
  against the **real patched header**, explicitly instantiating the local/global
  helper and parameter-map template. No precompiled header or native artifacts
  were changed. Log: `build/doris-jni-utf8-patch-syntax.log` in our connector repo.
- Standalone reproduction on Temurin 17.0.19 and the BE SDK's OpenJDK 17.0.2.
- Standalone proposed-decoder tests with the SDK's simdutf 9.2.0 and JDK 17.0.2.

Still for Doris maintainers: full native build/link and regression coverage of
parameter-map key/value round trips, scanners/writers sharing the utility,
non-BMP SQL/identifiers, malformed input, and existing ASCII/BMP workloads.
Verify the failing JDBC query against a rebuilt BE. Exact malformed-input
diagnostics are not a compatibility contract.
