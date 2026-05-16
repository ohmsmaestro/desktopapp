package com.readaccess.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.concurrent.Task;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class UploadService {

    // Columns stripped from the JSON payload before sending to the API
    private static final Set<String> EXCLUDED_COLUMNS = Set.of(
            "id", "sent", "retry_count", "created_by", "create_at", "createdBy", "createAt"
    );

    // Jackson derives these from getter names with consecutive-uppercase leading run logic:
    // getDOfB()  → strip "get" → "DOfB"  → lowercase run "DO" → "dofB"
    // getNOfSub()→ strip "get" → "NOfSub"→ lowercase run "NO" → "nofSub"
    private static final Map<String, String> KEY_RENAMES = Map.of(
            "dOfB",   "dofB",
            "nOfSub", "nofSub"
    );

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    /**
     * Creates a background Task that reads unsent rows from PostgreSQL
     * and POSTs them to the remote API, marking each sent=true on success.
     *
     * @param examType   exam type enum
     * @param examYear   e.g. 2024
     * @param dbConfig   PostgreSQL connection config
     * @param apiConfig  API endpoint + timeout config
     * @param onLog      log message callback (called on FX thread)
     * @param onProgress (processed, sent, failed) counts
     */
    public static Task<Void> createTask(
            ImportService.ExamType examType,
            int examYear,
            DbConfig dbConfig,
            ApiConfig apiConfig,
            Consumer<String> onLog,
            TriConsumer<Integer, Integer, Integer> onProgress) {

        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                String table = tableFor(examType);
                String apiUrl = apiConfig.getEndpointUrl() + examType.key;

                log("Connecting to PostgreSQL…");
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(apiConfig.getConnectionTimeoutSeconds()))
                        .version(HttpClient.Version.HTTP_2)
                        .build();

                try (Connection pg = DriverManager.getConnection(
                        dbConfig.getJdbcUrl(), dbConfig.getUsername(), dbConfig.getPassword())) {

                    // Warm up TCP+TLS (and HTTP/2 handshake) before the batch loop
                    try {
                        HttpRequest warmup = HttpRequest.newBuilder()
                                .uri(URI.create(apiUrl))
                                .timeout(Duration.ofSeconds(apiConfig.getConnectionTimeoutSeconds()))
                                .header("Accept", "application/json")
                                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                .build();
                        http.send(warmup, HttpResponse.BodyHandlers.discarding());
                        log("API connection established.");
                    } catch (Exception ignored) { /* non-fatal — first POST will retry */ }

                    log("Connected. Fetching unsent records from " + table + " for year " + examYear);

                    long total = countUnsent(pg, table, examYear);
                    log("Total unsent records: " + total);
                    updateProgress(0, total);

                    int[] counts = {0, 0, 0}; // [processed, sent, failed]
                    long lastId = 0;

                    ExecutorService pool = Executors.newFixedThreadPool(apiConfig.getParallelThreads());
                    try {
                        while (!isCancelled()) {
                            List<Map<String, Object>> batch = fetchBatch(pg, examType, table, examYear,
                                    lastId, apiConfig.getBatchSize());

                            if (batch.isEmpty()) {
                                log("No more unsent records found.");
                                break;
                            }

                            log("Processing batch of " + batch.size() + " records…");

                            // Submit all records in batch concurrently; result = [id, 1=success/0=fail]
                            CompletionService<long[]> cs = new ExecutorCompletionService<>(pool);
                            int submitted = 0;
                            for (Map<String, Object> row : batch) {
                                if (isCancelled()) break;
                                long id = ((Number) row.get("id")).longValue();
                                lastId = id;
                                String json = GSON.toJson(toPayload(row));
                                cs.submit(() -> new long[]{id,
                                        postWithRetry(http, apiUrl, examType.name(),
                                                json, apiConfig.getMaxRetries(), onLog) ? 1 : 0});
                                submitted++;
                            }

                            // Collect results as they complete
                            for (int i = 0; i < submitted; i++) {
                                long[] result = cs.take().get();
                                counts[0]++;
                                if (result[1] == 1) {
                                    markSent(pg, table, result[0]);
                                    counts[1]++;
                                } else {
                                    counts[2]++;
                                }
                                updateProgress(counts[0], total);
                                final int p = counts[0], s = counts[1], f = counts[2];
                                javafx.application.Platform.runLater(() -> onProgress.accept(p, s, f));
                            }

                            log("Batch done — sent: " + counts[1] + "  failed: " + counts[2]);
                        }
                    } finally {
                        pool.shutdownNow();
                    }

                    log("Upload complete. Processed: " + counts[0]
                            + "  Sent: " + counts[1] + "  Failed: " + counts[2]);
                }
                return null;
            }

            private void log(String msg) {
                javafx.application.Platform.runLater(() -> onLog.accept(msg));
            }
        };
    }

    // ── Column lists — only what each server DTO expects ─────────────────────

    private static final String COLS_BECE =
        "id, candidate_identifier, exam_type, schnum, sch_name, reg_no, cand_name, sex, d_of_b," +
        " subj1, grade1, subj2, grade2, subj3, grade3, subj4, grade4, subj5, grade5," +
        " subj6, grade6, subj7, grade7, subj8, grade8, subj9, grade9," +
        " n_of_sub, reason, debt, exam_year, show_photo, has_photo, show_dob," +
        " rem1, rem2, rem3, rem4, rem5, rem6, rem7, rem8, rem9, published," +
        " subj10, grade10, subj11, grade11, subj12, grade12, rem10, rem11, rem12," +
        " serial_num, resmth, reseng";

    private static final String COLS_SSCE =
        "id, candidate_identifier, exam_type, schnum, sch_name, reg_no, cand_name, sex, d_of_b," +
        " subj1, grade1, subj2, grade2, subj3, grade3, subj4, grade4, subj5, grade5," +
        " subj6, grade6, subj7, grade7, subj8, grade8, subj9, grade9," +
        " n_of_sub, reason, debt, exam_year, show_photo, has_photo, show_dob," +
        " rem1, rem2, rem3, rem4, rem5, rem6, rem7, rem8, rem9, published";

    private static final String COLS_NCEE =
        "id, candidate_identifier, cand_name, age, state_name, centre_name," +
        " maths, eng, quant, verbal, total, sex, state_of_origin," +
        " school1, school2, school3, school4, school5, school6, exam_no, exam_year";

    private static final String COLS_GIFTED =
        "id, candidate_identifier, cand_name, age, state_name, centre_name," +
        " maths, eng, quant, verbal, total, sex, school1, exam_no, exam_year, remark";

    private static String columnsFor(ImportService.ExamType type) {
        return switch (type) {
            case BECE    -> COLS_BECE;
            case EXTERNAL, INTERNAL -> COLS_SSCE;
            case NCEE    -> COLS_NCEE;
            case GIFTED  -> COLS_GIFTED;
        };
    }

    // ── Database helpers ──────────────────────────────────────────────────────

    private static List<Map<String, Object>> fetchBatch(
            Connection pg, ImportService.ExamType examType,
            String table, int examYear, long lastId, int batchSize) throws SQLException {

        String sql = "SELECT " + columnsFor(examType) + " FROM " + table
                + " WHERE exam_year = ? AND (sent IS NULL OR sent = false) AND id > ?"
                + " ORDER BY id LIMIT ?";

        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement pst = pg.prepareStatement(sql)) {
            pst.setInt(1, examYear);
            pst.setLong(2, lastId);
            pst.setInt(3, batchSize);
            ResultSet rs = pst.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static long countUnsent(Connection pg, String table, int examYear) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE exam_year = ? AND (sent IS NULL OR sent = false)";
        try (PreparedStatement pst = pg.prepareStatement(sql)) {
            pst.setInt(1, examYear);
            ResultSet rs = pst.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static void markSent(Connection pg, String table, long id) throws SQLException {
        try (PreparedStatement pst = pg.prepareStatement(
                "UPDATE " + table + " SET sent = true WHERE id = ?")) {
            pst.setLong(1, id);
            pst.executeUpdate();
        }
    }

    // ── Payload helpers ───────────────────────────────────────────────────────

    private static Map<String, Object> toPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (EXCLUDED_COLUMNS.contains(e.getKey())) continue;
            Object value = e.getValue();
            if (value == null) continue;
            if (value instanceof String s && s.isEmpty()) continue;
            // Normalise BigDecimal (numeric columns) to Long so Gson never emits "11.0"
            // which Jackson refuses for Integer fields
            if (value instanceof BigDecimal bd) {
                try { value = bd.longValueExact(); }
                catch (ArithmeticException ignored) { /* has decimals — keep as-is */ }
            }
            String key = toCamelCase(e.getKey());
            payload.put(KEY_RENAMES.getOrDefault(key, key), value);
        }
        return payload;
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { upper = true; }
            else if (upper) { sb.append(Character.toUpperCase(c)); upper = false; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static boolean postWithRetry(HttpClient http, String url, String examType,
                                         String json, int maxRetries, Consumer<String> onLog) {
        String idempotencyKey = sha256(json);
        String requestId = UUID.randomUUID().toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Exam-Type", examType.toUpperCase())
                .header("X-Request-Id", requestId)
                .header("X-Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        int attempt = 0;
        while (attempt <= maxRetries) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) return true;

                boolean transient_ = status == 408 || status == 429 || status >= 500;
                if (!transient_ || attempt >= maxRetries) {
                    // Log full server response so we can see the exact Jackson error
                    javafx.application.Platform.runLater(() ->
                            onLog.accept("API error " + status + ": " + response.body()));
                    return false;
                }

                attempt++;
                Thread.sleep(2000L * attempt);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    javafx.application.Platform.runLater(() ->
                            onLog.accept("Network error: " + e.getMessage()));
                    return false;
                }
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String tableFor(ImportService.ExamType type) {
        return switch (type) {
            case EXTERNAL -> "results_external";
            case INTERNAL -> "results_internal";
            case BECE     -> "results_bece";
            case NCEE     -> "results_ncee";
            case GIFTED   -> "results_gifted";
        };
    }

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
