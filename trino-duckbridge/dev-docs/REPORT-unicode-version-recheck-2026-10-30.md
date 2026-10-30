# Unicode-version gap recheck — 2026-10-30

## Verdict

**EV-E3 is resolved for our current Trino 483 / JDK 25 / parity 0.5.1 profile.**
The old open checkbox described ICU 66.1 / Unicode 13. The pinned parity
submodule already upgraded its independent static ICU to **76.1 / Unicode 16**
in 0.5.0, retaining that implementation in 0.5.1. No ICU replacement, native
rebuild, extension repin or peer change is needed for this recheck.

This is new verification of the actual bundled Linux AMD64 release binary,
not a conclusion drawn from matching version labels:

- DuckDB runtime: **v2.0.0**; loaded extension: **v0.5.1**.
- Bundled binary SHA-256:
  `f71c3e89447129c99f57d2ea55bd81f488122eba1dc5a68db9bd5f91cc5c921e`.
- Actual test JVM: **25.0.3+9-LTS**; Trino **483**.
- Normal production extraction and installed-extension loading are used; the
  signed binary is not loaded by disabling signature verification.

## Independent scalar and sequence proof

`TestUnicodeVersionParity` enumerates **all 1,112,064 valid Unicode scalar
values**, excluding UTF-16 surrogates but including unassigned values and
noncharacters. It compares nine results per scalar against independent JDK
`Character`/`Normalizer` behavior: simple lower/upper, trim/ltrim/rtrim on the
singleton, singleton NFC, and trim/ltrim/rtrim around a non-whitespace center.
All **10,008,576 comparisons pass**, with zero mismatches.

`SemanticFixtures` now retains 88 version-sensitive case-mapping inputs,
checked in both directions against actual native Trino, not hand-written expected
strings. These cover the original Glagolitic U+2C2F/U+2C5F failure, every Vithkuqi
case pair and identity holes, and Unicode-16 Cyrillic/Latin additions. Five
whole-string NFC cases cover Todhri, Tulu-Tigalari, Gurung Khema and Kirat Rai
composition plus canonical combining-class reordering. They supplement, rather
than replace, the prior whitespace/normalization corpus.

## Actual predicate and transport proof

- Embedded pushed-vs-local row-set tests retain NULL controls and independently
  verify distributed plans for six Unicode-14/16 lower/upper predicates.
- The real Quack path seeds the same adversarial strings, proves those case
  predicates actually push, and compares returned row IDs with native Trino
  evaluation over VALUES. The signed extension is installed on the real server.
- NFC composition also returns native-Trino-correct rows. Its planner overload
  is deliberately asserted **local**, not claimed pushed: Trino rewrites
  `normalize(s)` to arity 2 with `'NFC'`, while our emission catalog declares only
  arity 1. No new overload or normalization mode is enabled by this investigation.

## Verification and scope

```bash
./gradlew :trino-duckbridge:test \
  --tests '*TestUnicodeVersionParity' \
  --tests '*TestPushdownSemanticFixtures' \
  --tests '*TestPushdownRowSetParity' \
  --tests '*TestDuckBridgeQuackTransport' :trino-duckbridge:detekt
```

**629 targeted tests pass**, zero failures/errors/skips; lint passes. Evidence:
`build/http-unicode-investigation/unicode-targeted.log`,
`unicode-targeted-summary.json` and saved `unicode-targeted-results/` XML.

This closes the old Unicode-data mismatch for the current profile. It does not
promise compatibility with arbitrary future JDK/Trino versions, constitute a new
cross-platform exhaustive run, or re-run the entire Unicode normalization
conformance corpus. The scalar test fails explicitly if the qualified JVM major
changes; a new profile needs its own proof rather than an assumed version floor.
