# REAL membership: Trino 483 versus 484

## Result

The specific negative-zero IN/NOT IN discrepancy is present in Trino 483 and
fixed in stock Trino 484. This is an engine result, distinct from qualifying the
DuckBridge connector against each ABI.

REAL is SQL's 32-bit floating-point type. IN tests membership, for example
`x IN (0,1)`. In 483 the compiled DIRECT_SWITCH path compares long-carried REAL
bits, while equality/folded/domain paths equate -0.0 and +0.0. The connector's
diagnostic preserves both engine behaviors and the exact affected source IDs;
it never normalizes source values or accepts arbitrary differences.

## 484 source and stock runtime proof

Checksum-verified Maven `trino-main-484-sources.jar` has SHA-1
`58a0a1ba39a78c404f45626e8f916dceb8119451`.
`InCodeGenerator.isDirectLongComparisonValidType` excludes REAL from direct long
comparison; REAL now selects HASH_SWITCH for this small-list case.

An isolated official `trinodb/trino:484` container reported `SELECT version()=484`.
Image digest: `sha256:2704eb53935af5e582d63d6033e9bf99d20c325e9b0e30b1016ef59a4773979f`.
No connector or production dependency change was used for that native probe.

The five inputs were REAL -0.0, +0.0, 1.0, 2.0 and NULL, with IDs 1..5.
`to_hex(to_ieee754_32(x))` preserved `80000000` versus `00000000` for the zeros.
Folded VALUES and compiled UNNEST both returned IDs 1/2/3 for IN, ID 4 for NOT IN;
projected membership returned TRUE/TRUE/TRUE/FALSE/NULL. Source signs and SQL
three-valued logic were preserved. This proves the selected counterexample is
fixed, not every possible membership/NaN/compiler shape.

Evidence: `build/release-closeout/trino-484/source-comparison.json`,
`InCodeGenerator-484.java`, `runtime-484.json` and the native server log. A first
probe launcher assumed Python was installed inside the minimal image; that
launcher error is retained separately and is not an engine observation. The
working probe used a host client against only the owned container's private
address. The container was removed after the test; no live data or peer stack
was changed.

## Connector regression contract

The build passes its selected engine version into tests and verifies the actual
loaded Trino JAR identity. Both variants run IN/NOT IN, folded input, runtime input,
independent equality sets and physical connector scans. The 483 test expects only
the precise negative-zero discrepancy; 484 requires equality with the fixed
engine result. Neither version skips the test. Separate full connector and plugin
packaging results belong in the [publication receipt](REPORT-release-publication-2026-11-04.md).
