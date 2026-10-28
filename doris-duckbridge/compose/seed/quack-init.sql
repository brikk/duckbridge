-- Seed the in-memory DuckDB (behind Quack) with a schema + tables so the doris-duckbridge
-- metadata plane (probe P4) has something to resolve: SHOW DATABASES / SHOW TABLES / DESC.
-- Run by the quack entrypoint (/seed/init.sql) before the server starts serving.
--
-- Kept to types the P4 type map handles cleanly (scalars + HUGEINT + DECIMAL + DATE/TIMESTAMP +
-- BLOB + LIST + native UUID) so DESC succeeds end-to-end. STRUCT/MAP/JSON map to STRING; INTERVAL/UHUGEINT
-- are deliberately NOT seeded (they fail loud by design — exercised by the unit/integration tests,
-- not the happy-path smoke).

CREATE SCHEMA IF NOT EXISTS sales;

CREATE TABLE IF NOT EXISTS sales.customers (
    id        BIGINT,
    name      VARCHAR,
    balance   DECIMAL(18,2),
    signup    DATE,
    big_id    HUGEINT,
    tags      VARCHAR[],
    payload   BLOB,
    uuid_value UUID
);

INSERT INTO sales.customers VALUES
    (1, 'Alice',  100.50, DATE '2020-01-15', 170141183460469231731687303715884105727,
        ['vip','eu'], from_hex('80FF000100'), UUID '00112233-4455-6677-8899-aabbccddeeff'),
    (2, 'straße', 42.00,  DATE '2021-06-30', -5,
        ['de'], from_hex('41004200'), UUID 'ffffffff-ffff-4fff-8fff-ffffffffffff'),
    (3, 'δοκιμή', 0.00,   DATE '2022-12-01', 0, [], NULL, NULL);

-- placed_at is a naive TIMESTAMP (zone-independent predicates); shipped_at is a TIMESTAMPTZ so
-- the smoke can exercise the P3/P6 zone-explicit (`TIMESTAMPTZ '…+00'`) rendering end-to-end.
CREATE TABLE IF NOT EXISTS sales.orders (
    order_id   BIGINT,
    cust_id    BIGINT,
    amount     DOUBLE,
    placed_at  TIMESTAMP,
    shipped_at TIMESTAMPTZ
);

INSERT INTO sales.orders VALUES
    (100, 1, 19.99, TIMESTAMP '2023-03-01 10:00:00', TIMESTAMPTZ '2023-03-01 18:00:00+00'),
    (101, 2, 5.00,  TIMESTAMP '2023-03-02 11:30:00', TIMESTAMPTZ '2023-03-02 20:00:00+00');

-- Distinct from predicate fixtures: pre-epoch fractions, DST-overlap instants and SQL NULL
-- must survive the non-UTC Quack/JVM boundaries without losing microseconds.
CREATE TABLE IF NOT EXISTS sales.temporal_edges (
    id BIGINT,
    local_value TIMESTAMP,
    instant_value TIMESTAMPTZ
);
INSERT INTO sales.temporal_edges VALUES
    (1, TIMESTAMP '1969-12-31 23:59:59.123456', TIMESTAMPTZ '1970-01-01 08:59:59.123456+09'),
    (2, TIMESTAMP '2024-11-03 01:30:00.654321', TIMESTAMPTZ '2024-11-03 01:30:00.654321-07'),
    (3, TIMESTAMP '2024-11-03 01:30:00.654321', TIMESTAMPTZ '2024-11-03 01:30:00.654321-08'),
    (4, NULL, NULL);

-- New-function intake is deliberately separate from the established pushdown allowlist.
-- The audit evaluates host expressions above a real JDBC scan and compares UTF-8 bytes.
CREATE TABLE IF NOT EXISTS sales.function_inputs (id BIGINT, payload VARCHAR);
INSERT INTO sales.function_inputs VALUES
    (1, '{"a":1,"b":null,"arr":[null,2],"nested":{"z":0,"a":1}}'),
    (2, '{}'),
    (3, NULL),
    (4, '{"a":1,"a":2}'),
    (5, '{"a.b":1}'),
    (6, '[1,null,{"a":1}]'),
    (7, 'invalid');
