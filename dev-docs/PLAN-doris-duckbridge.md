# PLAN — `doris-duckbridge` (Route J: JDBC-over-Quack)

Status: exploratory plan. Targets Apache Doris's **new `fe-connector` catalog SPI**
(the `branch-catalog-spi` line / PR #62767), not yet in any Doris release.

## Goal & shape

A standalone `doris-duckbridge` connector that lets Doris push scans, projections,
and predicates down into **DuckDB running as a user-launched Quack server**, reached
over gizmo's pure-JVM `quack-jdbc` driver (`jdbc:quack://…`). This is the Doris sibling
to `trino-duckbridge` and reuses its most valuable asset — the **parity-backed pushdown
discipline** (push an expression to DuckDB only when DuckDB is *verified* to evaluate it
with engine-identical semantics). Note the careful wording: the *discipline, translator
architecture, and extension/test infrastructure* transfer; the *function catalog does
not* — parity is engine-specific, and Doris ≠ Trino (see §Parity oracle). The division
of labor is "FE does the predicate magic, the JDBC/Quack side does the rest": our plugin
composes the pushed-down DuckDB query on the Doris FE, and the user's own DuckDB process
executes it. We never read files ourselves — which is why formats the Doris BE can't
read (`.db`, vortex, lance, plus deletes / inlined data) come for free: real DuckDB does
the reading.

## The SPI we plug into

Doris's `fe-connector` SPI is a runtime plugin surface: a connector is an out-of-tree jar
(a `ServiceLoader`-discovered `ConnectorProvider`) dropped into `plugins/connector/<name>/`
and loaded at FE startup. The in-tree **`fe-connector-jdbc`** module is our structural
template — it already accepts an arbitrary `driver_class` + `driver_url` (whitelisted /
checksum-gated against `jdbc_drivers_dir`), supports **passthrough queries**, and emits a
`JDBC_SCAN` scan range. The FE plugin's job is metadata (schemas/tables/columns, resolved
by querying DuckDB via the driver at plan time) and **`planScan`**; the actual scan runs on
the **BE**, whose existing `JdbcJniReader` + `BaseJdbcExecutor` load the JDBC driver inside
the BE's embedded JVM and stream rows back. **The spike path rides the BE's existing `jdbc`
reader unmodified; the decided v1 additionally carries one small BE patch (the
`DuckDbTypeHandler`, §Dialect gap) — the plan is "one tiny reapplyable BE patch," not "zero
BE changes."**

## How we plug in (mechanics)

We implement the SPI's `ConnectorProvider` / `Connector` / `ConnectorMetadata` /
`ConnectorScanPlanProvider`. In `planScan`, the pushdown core translates the projected
columns + pushed-down filter (and pushable functions, plus LIMIT / `ORDER BY … LIMIT`
where the SPI hands them down — probe item P5) into a DuckDB `SELECT`, and we return
a `ConnectorScanRange` of type **`JDBC_SCAN`** with `getTableFormatType() == "jdbc"` and
`getFileFormat() == "jni"`, carrying in its `jdbc_params` map the `{driver_class,
driver_url, jdbc_url (jdbc:quack://user-host), query}`. The FE bridge (`PluginDrivenScanNode`)
passes `table_format_type="jdbc"` through to BE thrift verbatim, so the BE dispatches
`JdbcJniReader`, runs our query via `quack-jdbc` in its JVM, and returns JNI off-heap
columnar batches. Anything we can't prove-safe to push stays out of the query and is
re-evaluated by Doris above the scan — the same fail-safe split as the Trino side, with
the same hard rule: **never push through an unverified semantic; never silently
under/over-return.**

The translator itself is a **new frontend over a ported core**: Doris FE hands us its own
expression trees (not Trino `ConnectorExpression`s), so the walker is new code, while the
architecture — per-conjunct partial pushdown, per-argument type-tier gates, a curated
allowlist with a fixture per entry — ports from `trino-duckbridge`'s
`DuckBridgeExpressionTranslator` design.

## Parity oracle for Doris — probe first, alias only what diverges

The Trino side's contract is *Trino*-identical semantics, enforced by wrapping **every**
pushed function in a `trino_*` macro/native from the `trino_parity` extension — even
where DuckDB's built-in already matches. That blanket wrap isolates us from upstream
DuckDB behavior drift, but in hindsight it is **overkill**: the drift risk was already
low, and it forced a 95-entry macro catalog. We do not repeat that shape for Doris.

**Approach (decided): divergence-audit first, thin extension second.**

1. **Probe:** build a three-way fixture corpus per candidate function —
   `Doris(f)` vs `DuckDB-builtin(f)` on the same inputs (NULLs, unicode/astral,
   negative/overflow, DST-boundary datetimes, extreme values). Run it live against a
   branch-built Doris and the pinned DuckDB. This is mechanical and cheap; it reuses the
   fixture inputs already curated for the Trino catalog (the monorepo audits —
   string/unicode, hash NULL-handling, datetime-tz — are archived in this repo's
   `trino-duckbridge/dev-docs/archive/` and seed the input lists).
2. **Classify each candidate:**
   - **identical** → push the DuckDB **built-in natively**, no macro. Entry goes in the
     Doris allowlist with its fixture pinned as a regression test.
   - **divergent but fixable** → a `doris_*` macro/native in a **new, thin
     `duckdb-doris-parity` plugin** of the extension repo (same build/bundling/CI
     infrastructure as `trino_parity`; separate macro set, separate `doris_meta()`).
     If the audit says we need 3 macros, we ship 3 — not 200.
   - **divergent and not worth fixing** → not pushable; Doris evaluates above the scan.
3. **Drift guards, both directions:** the allowlist ⇄ `doris_meta()` lockstep test
   (ports from `TestTrinoFunctionAliases`) for whatever macros exist, **plus** — because
   natively-pushed functions are exposed to upstream DuckDB behavior change in a way the
   Trino side never was — the fixture corpus of *natively pushed* entries runs against
   the pinned DuckDB as a canary. Bump the DuckDB pin → the canary re-proves every
   native entry.

**v1 scope:** start with the domain floor (comparisons, IN, ranges, IS NULL — no
function semantics involved) plus the audited subset of high-value scalars (string
length/substr, arithmetic, date extraction). Grow the allowlist entry-by-entry with
fixtures, exactly as the Trino catalog grew. The extension plugin is only created when
the audit finds its first divergent-but-fixable function; **zero macros is a legitimate
v1 outcome.**

## Timezone & session semantics (unresolved — must be settled before tz pushdown)

The Trino side just relearned how sharp this knife is: session-zone alignment gates the
correctness of every tz-sensitive pushed expression, and the connector there **fails
loud** when the zone can't be set while tz pushdown is enabled. Route J has an extra
wrinkle: `JdbcJniScanner` runs a **verbatim single `query_sql`** — there is no obvious
hook for a per-connection `SET TimeZone` the way a live JDBC client owns its session.
Options, in preference order (probe item P3):

1. quack-jdbc connection properties / init-statement support (if the driver can run a
   session-init `SET TimeZone` before the query — check the driver, it's evolving).
2. Render zone-explicit SQL: only push tz-sensitive expressions in forms that carry the
   zone in the expression itself (`AT TIME ZONE '<zone>'` rewrites), never relying on
   the server's session zone.
3. Gate: no tz-sensitive pushdown at all until (1) or (2) is proven. **This is the v1
   default.** Doris evaluates datetime functions above the scan; DATE/naive-TIMESTAMP
   extraction (zone-independent) can still push once audited.

Also to pin down: Doris session `time_zone` variable propagation to the plugin's
`planScan` (does the SPI expose session state?), and Doris↔DuckDB `DATETIME` precision
mapping (Doris DATETIME(6) vs DuckDB micros — likely clean, verify).

## Quack operational notes

- **Pool pressure (probe item P2):** Quack 1.5.4's fixed server-side connection pool
  exhausts under per-split churn — this gated `trino-duckbridge`'s T2 engine. Route J's
  exposure depends on how many `JDBC_SCAN` ranges the FE emits per query and how the
  BE's `JdbcJniScanner` manages connections (per-range? pooled per BE?). Measure before
  assuming; if it's one range per query (likely for a JDBC scan), we're fine. Watch the
  upstream pool rework (1.5.5-ish) either way.
- **quack-jdbc maturity:** the FE resolves schemas/tables/columns through quack-jdbc
  `DatabaseMetaData` at plan time, and the BE decodes result sets through it. It's
  alpha; type fidelity must be corpus-validated for the *Doris* type map (the Trino-side
  validation doesn't transfer — different declared column types, different coercions).
  FSST/nested decode maturing upstream applies here identically.
- **Credentials:** Quack token into `jdbc_params` alongside url/driver — confirm the
  checksum-gated `driver_url` flow and that tokens don't leak into logs/EXPLAIN output.

## The dialect gap — pick the BE `TypeHandler`, don't "masquerade as Trino"

Doris's *stock* JDBC catalog is a closed dialect system — `JdbcDbType.parseFromUrl`
(`fe-connector-jdbc/.../JdbcDbType.java:58-82`) hard-`switch`es over a fixed DB set and
**throws** on an unknown URL prefix (`jdbc:quack://` is rejected outright), and it drives the
in-tree `JdbcQueryBuilder` / `JdbcIdentifierQuoter` / `JdbcFunctionPushdownConfig`. **But we
never touch that path.** Because we write our own SPI `ConnectorScanPlanProvider`, we compose
the DuckDB SQL with the pushdown core and emit our own `JDBC_SCAN` range — so `JdbcDbType`,
`JdbcQueryBuilder`, and the URL-prefix gate are all bypassed. What actually runs our scan on
the BE is **`JdbcJniScanner`** (`be/src/format/table/jdbc_jni_reader.cpp:38` → the Java
`org/apache/doris/jdbc/JdbcJniScanner`), and it reads a plain string map: `jdbc_url`,
`jdbc_user`, `jdbc_password`, `jdbc_driver_class`, `jdbc_driver_url`, **`query_sql` (our
verbatim SELECT, run as-is)**, and `table_type`.

The key finding: on this scanner path **`table_type` is just a free string that selects a
value-coercion `JdbcTypeHandler`**, via `JdbcTypeHandlerFactory.create(tableType)` whose
`default:` returns `DefaultTypeHandler` (`JdbcTypeHandlerFactory.java`). There is **no
`TOdbcTableType` enum gate and no throwing `default`** here — the throwing
`JdbcExecutorFactory` is the *legacy* executor path, not `JdbcJniScanner`. So "masquerade as
an enum" is the wrong frame; we simply choose the handler whose ResultSet→Doris coercions
best fit DuckDB. Coverage of the handlers' `getColumnValue` (what each can decode into a
declared Doris column):

| `table_type` → handler | LARGEINT (int128) | ARRAY (list) | VARBINARY (blob) | STRUCT / MAP |
|---|:--:|:--:|:--:|:--:|
| *(empty/unknown)* → `DefaultTypeHandler` | ✅ | ❌ | ✅ | ❌ |
| `CLICKHOUSE` → `ClickHouseTypeHandler` | ✅ | ✅ | ❌ | ❌ |
| `TRINO`/`PRESTO` → `TrinoTypeHandler` | ❌ | ✅ | ✅ | ❌ |

So **Trino is the wrong pick** — it's the one handler that drops `LARGEINT` (the FE declares
DuckDB `HUGEINT` → Doris `LARGEINT`, and Trino's handler throws on it). Recommendation:

- **v1 scalar core: send an empty `table_type` → `DefaultTypeHandler`.** It already covers
  `LARGEINT` **and** `VARBINARY` plus all scalars and `DECIMAL(≤38)` — strictly better than
  Trino for DuckDB, no masquerade needed.
- **When `LIST`/arrays matter: `table_type = CLICKHOUSE`.** Keeps `LARGEINT` and adds `ARRAY`
  (its element decoder already handles `BigInteger` lists, matching DuckDB int128/wide-unsigned
  lists). Cost: it has no `VARBINARY` arm, so declare `BLOB` columns as `STRING`/hex on the FE.
- **`STRUCT`/`MAP`: no handler decodes them at all.** For v1, map DuckDB `STRUCT`/`MAP`/`JSON`
  to `STRING`/`JSON` on the FE. First-class nested needs a custom handler (below).

**Decision: we patch in our own `DuckDbTypeHandler` from the start.** Rather than live with
the split above (`Default` = int128+blob but no arrays; `CLICKHOUSE` = int128+arrays but no
blob; `TRINO` = no int128), we add a first-class `DuckDbTypeHandler` to the BE `jdbc-scanner`
and a `case "DUCKDB"` in `JdbcTypeHandlerFactory`, then emit `table_type = DUCKDB` from our
connector. This is a **BE-only, be-java-extension change** (one new class + one string case):
it needs **no thrift `TOdbcTableType` change and no FE `JdbcDbType` change** — those bind only
the legacy stock-catalog path we don't use — so it stays a small, self-contained patch. One
handler then covers `LARGEINT` + `ARRAY` + `VARBINARY` + (eventually) `STRUCT`/`MAP` with
DuckDB-faithful coercions, instead of contorting the FE type map to fit someone else's dialect.

**Patch posture (until release):** this `DuckDbTypeHandler` (and the FE `SPI_READY_TYPES`
whitelist entry) live in **our patched FE/BE builds** — the same "we only run on our own
patched Doris" stance `doris-ducklake` takes. Both are kept as clean, reapplyable patches and
**broken out into an upstream Doris PR before we head toward release**, so a stock Doris
carrying the fe-connector SPI + our handler can eventually run the plugin unpatched. Until
that PR lands, the connector runs **only** on our branch-built, patched FE **and** BE.
(`DefaultTypeHandler`/`CLICKHOUSE` stay the zero-patch fallback for early spikes before the
handler exists.) How the patches and the Doris baseline are managed is §Managing the Doris
patches.

## Which Doris? We need the new-SPI line, for BOTH FE and BE

Verified against the checkout (`/home/jayson/DEV/OSS/doris`): **the released 4.1.x line —
including the `4.1.3` tag and its rc's — does NOT have any of what Route J needs.** In `4.1.3`:
the entire `fe/fe-connector` SPI is **absent** (0 files) and there is **no `SPI_READY_TYPES`**,
so a connector plugin cannot load at all; and the BE `jdbc-scanner` ships **only** the legacy
`*JdbcExecutor` classes + the throwing `JdbcExecutorFactory` — **no `JdbcJniScanner`, no
`JdbcTypeHandlerFactory`, no `*TypeHandler`**. All of that (the plugin SPI, the `JDBC_SCAN` /
`plugin_driven` range plumbing, `JdbcJniScanner`, and the string-keyed `TypeHandler` default
path the dialect section relies on) lives on **`branch-catalog-spi`** and is only now trending
into `master`. So:

- **This is NOT a stock-BE-with-branch-FE deal like `doris-ducklake`.** Ducklake can pair a
  stock BE because it rides the BE's *native Parquet/iceberg* reader, which exists in stock.
  Route J rides `JdbcJniScanner`, which is **not in stock 4.1.x** — so we need **both** the FE
  **and** the BE built from the branch (or master once it lands there). Plan on building the
  full Doris from that line, not layering onto `apache/doris:be-4.1.x`.
- **Stock 4.1.3 alone is a non-starter for the plugin.** Its in-tree JDBC catalog
  (`fe-core .../datasource/jdbc/client/JdbcClient.java`) does driver loading + pushdown +
  passthrough, but it is not pluggable, its pushdown is fe-core's (not ours), it routes
  through the legacy throwing executor path, and its URL-prefix gate rejects `jdbc:quack`.
  Getting duckbridge behavior on 4.1.3 means **forking fe-core** — throwaway-spike only.
- **Branch churn risk:** the SPI is pre-release and can move under us. Pin the exact
  branch commit we build against, and carry an SPI-canary test (compile + load the plugin
  against the pin) so an upstream rebase is a deliberate act, not a surprise.

## Deploy shape on the SPI branch

Ship duckbridge as an out-of-tree plugin with our own `ConnectorScanPlanProvider`, so the
pushdown core owns the SQL and nothing in fe-core is forked. Build FE **and** BE from the
`branch-catalog-spi` line (`build.sh --fe --be` → `output/`), install the plugin zip into the
FE's `plugins/connector/<name>/`, and carry a **one-line `SPI_READY_TYPES` whitelist patch**
in `CatalogFactory.java` for our catalog `type` (e.g. `duckdb`) — the SPI silently ignores any
provider whose `getType()` isn't whitelisted until upstream opens that list. (`jdbc` is already
whitelisted but routes to the in-tree `fe-connector-jdbc`, so we use a distinct type + the
patch rather than colliding.) A read-only connector needs only that whitelist guard; the
`pluginCatalogTypeToEngine` CREATE-TABLE patch `doris-ducklake` carries is unneeded unless we
add write/DDL.

**Bottom line:** target the `branch-catalog-spi` Doris (build FE **and** BE from it) and run
only on **our patched FE/BE** carrying two small, reapplyable patches — the FE
`SPI_READY_TYPES` whitelist entry and the BE `DuckDbTypeHandler` (+ its `JdbcTypeHandlerFactory`
case) — both managed per §Managing the Doris patches and slated to be **broken out into an
upstream Doris PR before release**. Neither
`4.1.2` nor `4.1.3` can run this (no fe-connector SPI, no `JdbcJniScanner`); a stock Doris only
becomes a target once a release ships the SPI and our handler PR lands.

## Managing the Doris patches

Precedent: `doris-ducklake` keeps its FE patches in-repo (`fe-patches/ducklake-fe.patch` +
`FE-PATCHES.md` with rationale, apply procedure, and a re-vendor log) and builds against an
external worktree at a **pinned commit**. That shape works, but its friction log exposes the
weak spot we must fix here: **`branch-catalog-spi` rebases constantly, so upstream SHAs get
GC'd** and the pin degrades to "check out the commit with this exact subject" — unacceptable
for reproducible builds, and fatal for any submodule-style pin. Decisions:

1. **Patch files are the canonical artifact, not fork commits.** They live in this repo at
   `doris-patches/` — `fe/0001-spi-ready-types-duckdb.patch`,
   `be/0001-duckdb-type-handler.patch` — each with a header stating what it does, why
   upstream needs it, and the upstream ask/PR link (the `FE-PATCHES.md` discipline).
   Keeping them as visible diffs (rather than burying them as commits on a fork branch)
   keeps the upstream-PR obligation impossible to forget and the delta reviewable at a
   glance.

2. **Pin immutability via a fork mirror, not upstream SHAs.** We maintain a
   `brikk/doris` fork; each validated baseline is pushed there as an immutable branch
   (`duckbridge/baseline-YYYYMMDD`, e.g. `duckbridge/baseline-20260719` = the
   `branch-catalog-spi` commit we researched and validated). Upstream can rebase and GC
   all it likes — our SHA survives. The current pin is recorded in exactly one file,
   `doris-patches/BASELINE` (SHA + upstream subject + date + fork branch), which docs and
   scripts read.

3. **No Doris submodule in this repo.** A full Doris clone is multi-GB and would tax every
   `duckbridge` checkout that never builds Doris. Instead a script,
   `tools/doris-baseline.sh`, does: clone/fetch the fork at the `BASELINE` pin into a
   cache dir → `git apply --3way --check` then apply each patch (fail loud on drift) →
   `build.sh --fe --be` → `mvn install` the SPI jars (the plugin compiles against them) →
   build the FE/BE overlay images. Applying patches **at build time from the canonical
   files** keeps a single source of truth; a derived, force-pushed convenience branch
   (`duckbridge/patched-YYYYMMDD`, baseline + patches applied, regenerated by script and
   never hand-edited) is optional sugar for CI.

4. **Prebuilt images so nobody rebuilds Doris casually.** The BE is a multi-hour C++
   build. CI (or a maintainer) publishes `doris-fe:duckbridge-<pin>` /
   `doris-be:duckbridge-<pin>` images once per baseline; compose and the test harness
   consume the images, and only baseline bumps or patch edits trigger a rebuild. Note the
   BE patch touches only `be-java-extensions/jdbc-scanner` (Java) — a patch-only change
   can rebuild that jar and overlay it into the existing BE image without re-running the
   C++ build.

5. **Re-vendor procedure (per baseline bump), with a log.** Fetch the new upstream tip →
   push it to the fork as a new dated baseline branch → `git apply --check` each patch,
   re-diff if anchors moved (record what moved and why it's benign — impact analysis à la
   the doris-ducklake re-vendor log) → rebuild images → run the connector suite + parity
   probes → update `BASELINE` + append the log entry in `doris-patches/PATCHES.md`. The
   SPI-canary test (§Which Doris) makes an un-bumped stale pin visible rather than
   silently rotting.

6. **Exit criteria.** Each patch is an upstream ask; the goal is deletion. When the
   fe-connector SPI + our `DuckDbTypeHandler` PR land in a Doris release, `BASELINE`
   points at a release tag, the fork mirror becomes optional, and `doris-patches/`
   empties to a tombstone.

## Verification & test harness

Pushdown you can't prove is pushdown you don't have. The Trino side's bar carries over:

- **Live parity harness:** every allowlist entry gets a fixture that runs the same query
  through Doris-with-pushdown and through Doris-with-pushdown-disabled (forced
  above-scan evaluation) and asserts identical results — the Doris analogue of
  `TestDuckBridgeArithmeticPushdownParity`. Plus the direct Doris-vs-DuckDB probe corpus
  from §Parity oracle.
- **Provable pushdown:** assert the generated `query_sql` (EXPLAIN or scan-range
  inspection) contains the pushed predicate, so a silent stop-pushing regression is a
  red test — the Doris analogue of `TestDuckBridgePushdown`.
- **Type round-trip matrix:** every Doris column type the FE can declare from DuckDB
  metadata, round-tripped through `JdbcJniScanner` + the chosen `TypeHandler`
  (unicode/astral strings, LARGEINT edges, DECIMAL(38) edges, date/datetime bounds).
- **Drift canaries:** `doris_meta()` lockstep (once macros exist), native-pushdown
  fixture canary on DuckDB pin bumps, SPI canary on the Doris branch pin.
- **Env:** compose with a branch-built FE+BE pair + a Quack server — `doris-ducklake`'s
  compose smoke setup is the template.

## Open probes (settle before building)

| # | Probe | Gates |
|---|---|---|
| P1 | Divergence audit: Doris vs DuckDB built-ins on the fixture corpus | Parity approach depth (how many `doris_*` macros, if any) |
| P2 | `JDBC_SCAN` range count per query + BE connection behavior vs Quack 1.5.4 pool | Whether the pool gate applies to Route J |
| P3 | Session-init capability of quack-jdbc / zone-explicit SQL rendering | Any tz-sensitive pushdown (v1 default: gated off) |
| P4 | quack-jdbc `DatabaseMetaData` fidelity for the Doris type map | FE metadata resolution trustworthiness |
| P5 | What the SPI hands `planScan` beyond conjuncts (limit? sort? aggregates?) | LIMIT/TopN pushdown scope |
| P6 | Doris session `time_zone` visibility from the plugin | §Timezone options 1–2 |

## Ceiling of Route J (and what's deliberately out of scope)

Route J transfers results via the BE's **JNI off-heap columnar** path, not Arrow — there is
no zero-copy Arrow ingestion for a plugin today. The BE *does* have an Arrow-Flight client
reader, but it's hardwired to Doris→Doris federation (`remote_doris`), and the SPI's
`"plugin_driven"` table-format default has **no BE reader behind it** (both BE dispatchers
are hardcoded allowlists). So a cleaner ADBC / Arrow-Flight transport (the analogue of
`trino-duckbridge`'s Arrow engine, e.g. via `adbc-driver-quack`) or a truly custom JNI
scanner would both require upstream **BE work** and are explicitly out of scope for Route J.
The pragmatic first cut is: reuse the `jdbc` BE reader by reporting `table_format_type="jdbc"`
(the same "ride a shared BE reader" move `doris-ducklake` makes with `iceberg`), keep all the
cleverness — dialect, pushdown, parity — on the FE, and let the user's Quack/DuckDB do the
execution.
