-- Seed the in-memory DuckDB (behind Quack) with a schema + tables so the doris-duckbridge
-- metadata plane (probe P4) has something to resolve: SHOW DATABASES / SHOW TABLES / DESC.
-- Run by the quack entrypoint (/seed/init.sql) before the server starts serving.
--
-- Kept to types the P4 type map handles cleanly (scalars + HUGEINT + DECIMAL + DATE/TIMESTAMP +
-- BLOB + LIST) so DESC succeeds end-to-end. STRUCT/MAP/JSON/UUID map to STRING; INTERVAL/UHUGEINT
-- are deliberately NOT seeded (they fail loud by design — exercised by the unit/integration tests,
-- not the happy-path smoke).

CREATE SCHEMA IF NOT EXISTS sales;

CREATE TABLE IF NOT EXISTS sales.customers (
    id        BIGINT,
    name      VARCHAR,
    balance   DECIMAL(18,2),
    signup    DATE,
    big_id    HUGEINT,
    tags      VARCHAR[]
);

INSERT INTO sales.customers VALUES
    (1, 'Alice',  100.50, DATE '2020-01-15', 170141183460469231731687303715884105727, ['vip','eu']),
    (2, 'straße', 42.00,  DATE '2021-06-30', -5, ['de']),
    (3, 'δοκιμή', 0.00,   DATE '2022-12-01', 0, []);

CREATE TABLE IF NOT EXISTS sales.orders (
    order_id  BIGINT,
    cust_id   BIGINT,
    amount    DOUBLE,
    placed_at TIMESTAMP
);

INSERT INTO sales.orders VALUES
    (100, 1, 19.99, TIMESTAMP '2023-03-01 10:00:00'),
    (101, 2, 5.00,  TIMESTAMP '2023-03-02 11:30:00');
