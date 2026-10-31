package dev.brikk.duckbridge.trino.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Properties;

/** JDK-only child entry point: its classpath contains exactly one native JDBC release. */
public final class StorageCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        var properties = new Properties();
        String operation = args[2];
        if (operation.equals("read")) properties.setProperty("duckdb.read_only", "true");
        if (!args[3].equals("default")) properties.setProperty("storage_compatibility_version", args[3]);
        try {
            if (operation.equals("upgrade")) {
                try (var connection = DriverManager.getConnection("jdbc:duckdb:")) {
                    version(connection, args[0]);
                    execute(connection, "ATTACH '" + literal(args[1]) + "' AS original (READ_ONLY)");
                    execute(connection, "ATTACH '" + literal(args[4]) + "' AS upgraded (STORAGE_VERSION 'latest')");
                    execute(connection, "COPY FROM DATABASE original TO upgraded");
                    execute(connection, "CHECKPOINT upgraded");
                }
                return;
            }
            try (var connection = DriverManager.getConnection("jdbc:duckdb:" + args[1], properties)) {
                version(connection, args[0]);
                execute(connection, "SET threads=2");
                if (operation.equals("seed")) seed(connection);
                if (operation.equals("write")) {
                    execute(connection, "UPDATE inventory SET amount=amount+1 WHERE id%13=0");
                    execute(connection, "DELETE FROM inventory WHERE id%17=0");
                    execute(connection, "CHECKPOINT");
                }
                snapshot(connection);
            }
        } catch (SQLException error) {
            System.err.println("SQL_ERROR " + error.getSQLState() + ": " + error.getMessage());
            System.exit(2);
        }
    }

    private static String literal(String value) { return value.replace("'", "''"); }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void version(Connection connection, String expected) throws SQLException {
        String actual = scalar(connection, "SELECT version()");
        if (!actual.equals("v" + expected)) throw new IllegalStateException("Wrong engine: " + actual);
        System.out.println("VERSION=" + actual);
    }

    private static void seed(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE inventory(id INTEGER PRIMARY KEY,amount DECIMAL(18,4),s VARCHAR,payload BLOB,d DATE,ts TIMESTAMP,n INTEGER)");
        execute(connection, "INSERT INTO inventory SELECT r,CAST(r*0.125 AS DECIMAL(18,4))," +
            "repeat('straße δοκιμή 😀 repeated ',8)||CAST(r%11 AS VARCHAR),unhex('00FF00')," +
            "DATE '1960-01-01'+CAST(r AS INTEGER),TIMESTAMP '1969-12-31 23:59:59.123456'," +
            "CASE WHEN r%3=0 THEN NULL ELSE r END FROM range(12000) t(r)");
        execute(connection, "CREATE INDEX amount_lookup ON inventory(amount)");
        execute(connection, "DELETE FROM inventory WHERE id%7=0");
        StringBuilder columns = new StringBuilder("r AS id");
        for (int i=0; i<64; i++) columns.append(",r+").append(i).append(" AS c").append(i);
        execute(connection, "CREATE TABLE wide AS SELECT " + columns + " FROM range(128) t(r)");
        execute(connection, "CHECKPOINT");
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("No scalar row: " + sql);
            return rows.getString(1);
        }
    }

    private static void snapshot(Connection connection) throws Exception {
        System.out.println("FORMAT=" + scalar(connection,
            "SELECT tags['storage_version'] FROM duckdb_databases() WHERE database_name=current_database()"));
        System.out.println("ROWS=" + scalar(connection, "SELECT count(*) FROM inventory"));
        System.out.println("WIDE=" + scalar(connection, "SELECT count(*) FROM information_schema.columns WHERE table_name='wide'"));
        System.out.println("INDEXES=" + scalar(connection, "SELECT count(*) FROM duckdb_indexes() WHERE index_name='amount_lookup'"));
        var digest = MessageDigest.getInstance("SHA-256");
        for (String sql : new String[] {
                "SELECT id,amount,s,hex(payload),CAST(d AS VARCHAR),CAST(ts AS VARCHAR),n FROM inventory ORDER BY id",
                "SELECT * FROM wide ORDER BY id"}) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                while (rows.next()) for (int i=1; i<=rows.getMetaData().getColumnCount(); i++) {
                    String value = rows.getString(i);
                    digest.update((byte)(value == null ? 0 : 1));
                    if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte)0xff);
                }
            }
        }
        System.out.println("DATA=" + HexFormat.of().formatHex(digest.digest()));
        var codecs = new LinkedHashSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA storage_info('inventory')")) {
            while (rows.next()) codecs.add(rows.getString("compression"));
        }
        System.out.println("COMPRESSION=" + codecs);
    }
}
