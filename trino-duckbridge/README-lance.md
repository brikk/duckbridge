# Lance Scan & Search

Path-based table functions that scan [Lance](https://lancedb.github.io/lance/) datasets
and run vector / full-text / hybrid search, executed by the DuckDB `lance` extension.
**EXPERIMENTAL** — the surface may evolve with the upstream extension.

The functions take the dataset **path** directly: name a path, the connector scans it.

## Enabling

Off by default. Enable on the catalog:

```properties
duckbridge.lance.enabled=true
```

When enabled, the connector `INSTALL`s + `LOAD`s the `lance` extension on every DuckDB
connection. If the extension can't be installed or loaded, using these functions fails
with install instructions — never a silent empty result.

On a **remote (Quack) server** the extension is a server-side concern: the connector
issues INSTALL/LOAD over the connection, but the server needs extension-repository
access (or the extension pre-installed).

## Scanning

```sql
SELECT id, txt FROM TABLE(duckdb.system.lance_scan(path => '/data/docs.lance')) ORDER BY id;
```

| Function | Arguments | Returns |
|---|---|---|
| `lance_scan` | `path` (dataset directory) | the dataset's columns as-is |

`ARRAY` columns (embeddings `FLOAT[n]`, `VARCHAR[]` tags, ...) are fully supported;
scalars, arrays, and unicode content round-trip.

## Search

Three functions, all path-based with `k => 10`, `prefilter => false` defaults. They
return the dataset's columns **plus** score column(s):

| Function | Extra arguments | Appended columns | Ordering |
|---|---|---|---|
| `lance_vector_search` | `column` (embedding), `query_vector ARRAY(DOUBLE)`, `k`, `prefilter` | `_distance REAL` | ascending distance |
| `lance_fts` | `column` (text), `query VARCHAR`, `k`, `prefilter` | `_score REAL` | descending BM25 score |
| `lance_hybrid_search` | `vector_column`, `query_vector`, `text_column`, `query`, `k`, `alpha DOUBLE` (optional), `prefilter` | `_distance`, `_score` (NULL when no text match), `_hybrid_score` | descending hybrid score |

```sql
SELECT id, title, _distance
FROM TABLE(duckdb.system.lance_vector_search(
        path         => '/data/docs.lance',
        column       => 'emb',
        query_vector => ARRAY[0.12, 0.85, 0.03],
        k            => 10))
ORDER BY _distance
LIMIT 10;
```

**Multiple fragments.** A lance dataset may hold multiple fragments; the search returns
up to `k` per the extension's semantics (best-effort "around k" for FTS). Wrap with
`ORDER BY _distance LIMIT k` (or `_score DESC` / `_hybrid_score DESC`) for the exact
global answer.

**FTS needs no index** — brute-force matching works on unindexed text columns.

## Filtering

A `WHERE` / `ORDER BY ... LIMIT` over these functions is applied by Trino above the
function, not pushed into the generated lance SQL. Results are correct either way. The
`prefilter` flag is forwarded to the DuckDB function (it changes lance's
filter-then-search semantics and FTS corpus statistics).

## Extension versioning

The `lance` extension comes from DuckDB's extension repository (latest build per DuckDB
version + platform; a hard version pin isn't possible). The connector's test suite
includes a canary that verifies every call shape against the currently served build.

## Limitations

- **Read/search only.** No writes (`COPY`, CTAS, INSERT). Write lance datasets with
  DuckDB or any lance writer out of band, then scan/search them by path.
- **`s3://` paths** pass through to DuckDB as-is; the extension reads process-global
  `AWS_*` environment credentials, which the operator must provide.
