import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConcurrentOpenClose {
    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        Path directory = args.length > 1 ? Path.of(args[1]) : Path.of(".");
        Path file = Files.createTempFile(directory, "duckdb-open-close-", ".db");
        Files.delete(file); // DuckDB must create the database, not open an empty file.
        String url = "jdbc:duckdb:" + file.toAbsolutePath();
        var start = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var pool = Executors.newFixedThreadPool(threads);
        var workers = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200 && failure.get() == null; i++) {
                            try (var connection = DriverManager.getConnection(url);
                                 var statement = connection.createStatement();
                                 var rows = statement.executeQuery("SELECT 42")) {
                                if (!rows.next() || rows.getInt(1) != 42 || rows.next()) {
                                    throw new AssertionError("Unexpected query result");
                                }
                            }
                            completed.incrementAndGet();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    }
                }));
            }
            start.countDown();
            for (var worker : workers) {
                worker.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("Threads: " + threads + "; completed cycles: " + completed);
        System.out.println("Database: " + file.toAbsolutePath());
        if (failure.get() != null) {
            failure.get().printStackTrace();
            System.exit(1);
        }
        System.out.println("PASS");
    }
}
