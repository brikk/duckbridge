# Note: branch-catalog-spi #66135 — patch-free connectors + SPI churn

> **STATUS 2026-07-28 — DONE for duckbridge.** Re-vendored to pin `a0c10f0672b`. FE patch
> deleted (SPI_READY_TYPES gone), scan surface adapted (`planScan(session, ConnectorScanRequest)`;
> `getScanRangeType`/`estimateScanRangeCount`/`getRangeType`/`ConnectorScanRangeType` removed), BE
> patch re-verified clean. Read-side metadata + pushdown surface unchanged. `62` module tests +
> detekt green against SPI jars rebuilt from the pin. Details:
> `doris-patches/PATCHES.md` §Re-vendor log (2026-07-28) and
> `doris-duckbridge/dev-docs/duckbridge-doris-friction.md` (2026-07-28 entry). Operating model:
> local-only — patches applied against the local pinned worktree `~/DEV/OSS/doris-catalog-spi`, no
> `brikk/doris` fork push. The points below are the original heads-up from the doris-ducklake agent.

From the doris-ducklake re-vendor, 2026-07-27. New pin there: `a0c10f0672b`.
Full detail: `~/DEV/brikk/doris-ducklake/fe-patches/FE-PATCHES.md` (top re-vendor log
entry) and doris-ducklake commit `3c8b63f` (the exact adaptation diffs).
Upstream commit: `fce5af4e041` (#66135) on branch-catalog-spi.

## The headline: FE patches are probably dead for you too

#66135's goal is "adding a connector requires no edit to fe-core / fe-connector-api /
fe-connector-spi". Concretely:

- `CatalogFactory.SPI_READY_TYPES` is gone. A registered `ConnectorProvider` whose
  `getType()` matches the `CREATE CATALOG` type wins. No whitelist to patch.
- `CreateTableInfo.pluginCatalogTypeToEngine` is gone. `ENGINE=` is optional and
  connector-owned: `ConnectorProvider.acceptedCreateTableEngineNames()` (default: accept
  none; omitting ENGINE is always legal). `PARTITION BY` / `DISTRIBUTED BY` validation is
  the connector's `createTable` job. `displayEngineName()` defaults to `getType()` —
  ducklake tables now show `Engine: ducklake`; quack would show its own type.

doris-ducklake now builds the branch pristine. Check `doris-patches/` here against the
same two anchors — if that's what your patches touch, they can go.

## Compile churn checklist (what broke doris-ducklake; all mechanical)

1. `planScan` 4/6/7-arg overloads collapsed into one:
   `planScan(session, ConnectorScanRequest)` — request carries handle, columns, filter,
   limit, requiredPartitions, countPushdown.
2. `ConnectorScanRange.getRangeType()` / `.getDeleteFiles()` removed; the
   `ConnectorScanRangeType` and `ConnectorDeleteFile` types are deleted. EXPLAIN
   delete-file listing moved to `ConnectorScanPlanProvider.getDeleteFiles(TTableFormatFileDesc)`.
3. `ConnectorMvccSnapshot.Builder.timestampMillis(...)` removed.
4. `supportsCreateDatabase()` removed (overriding `createDatabase` IS the declaration);
   `dropDatabase` gained a `force` arg (reject it if you have no CASCADE).
5. `ConnectorPropertyMetadata` deleted — validate catalog properties with plain code.
6. `ConnectorWriteHandle.getWriteContext()` → `getStaticPartitionSpec()`.
7. `ConnectorPartitionSpec` third ctor arg: `List` → `hasExplicitPartitionValues: Boolean`.
8. `ConnectorType.of("STRUCT")` now rejects childless structs — use `structOf(...)`.

Pushdown surface (`applyFilter`/`applyProjection`/`ConnectorPushdownOps`) unchanged —
quack's pushdowns should be untouched apart from the `planScan` signature.

## Validation that proved it for ducklake

Rebuild SPI jars from the tip (`mvn install -P flatten -pl
fe-connector/fe-connector-api,fe-connector/fe-connector-spi,fe-thrift`), recompile, then
live-check: `CREATE CATALOG` on an unpatched FE, and `CREATE TABLE ... PARTITION BY LIST
(bucket(...)) ()` with no `ENGINE=` (the transform-style partition parse works on the
generic path now — that was the one semantic the old ENGINE padding provided).

## Still broken upstream (unchanged by #66135), if you share these paths

- Bare `COUNT(<nullable col>)` on a plugin scan is non-deterministic (scan slot
  `colUniqueId=-1`; #65548/#65782 count-pushdown port). Details:
  doris-ducklake friction log, 2026-07-22 entry.
- BE position-delete reader rejects OPTIONAL delete-file columns (iceberg-spec REQUIRED).
- No channel for FE-computed rows to reach the BE (matters if quack ever materializes
  rows FE-side).
