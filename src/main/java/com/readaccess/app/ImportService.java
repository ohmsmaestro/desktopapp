package com.readaccess.app;

import javafx.concurrent.Task;

import java.sql.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ImportService {

    public enum ExamType {
        EXTERNAL("external"),
        INTERNAL("internal"),
        BECE("bece"),
        NCEE("ncee"),
        GIFTED("gifted");

        public final String key;
        ExamType(String key) { this.key = key; }

        public String getLabel() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    /**
     * Creates a background Task that imports all tables from the Access file
     * into the configured PostgreSQL database.
     *
     * @param accessFilePath  absolute path to the .accdb / .mdb file
     * @param examType        which exam schema to use
     * @param dbConfig        target PostgreSQL connection details
     * @param onLog           callback for log messages (runs on FX thread via Platform.runLater)
     * @param onProgress      (processed, uploaded) row counts
     */
    public static Task<Void> createTask(
            String accessFilePath,
            ExamType examType,
            DbConfig dbConfig,
            Consumer<String> onLog,
            BiConsumer<Integer, Integer> onProgress) {

        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                Class.forName("net.ucanaccess.jdbc.UcanaccessDriver");

                String accessUrl = "jdbc:ucanaccess://" + accessFilePath + ";memory=false;sysSchema=true";
                log("Connecting to Access file…");

                try (Connection accessConn = DriverManager.getConnection(accessUrl);
                     Connection pgConn = DriverManager.getConnection(
                             dbConfig.getJdbcUrl(), dbConfig.getUsername(), dbConfig.getPassword())) {

                    pgConn.setAutoCommit(false);
                    log("Connected to PostgreSQL at " + dbConfig.getJdbcUrl());

                    Statement stmt = accessConn.createStatement();
                    int[] counts = {0, 0}; // [processed, uploaded]

                    try (ResultSet tables = accessConn.getMetaData().getTables(null, null, null, null)) {
                        while (tables.next()) {
                            if (isCancelled()) {
                                log("Import cancelled.");
                                break;
                            }
                            String tblName = tables.getString("TABLE_NAME");
                            log("Processing table: " + tblName);

                            switch (examType) {
                                case EXTERNAL -> importExternalInternal(accessConn, pgConn, stmt, tblName,
                                        "EXTERNAL", "results_external", counts, onLog, onProgress);
                                case INTERNAL -> importExternalInternal(accessConn, pgConn, stmt, tblName,
                                        "INTERNAL", "results_internal", counts, onLog, onProgress);
                                case BECE    -> importBece(accessConn, pgConn, stmt, tblName,
                                        counts, onLog, onProgress);
                                case NCEE    -> importNcee(accessConn, pgConn, stmt, tblName,
                                        counts, onLog, onProgress);
                                case GIFTED  -> importGifted(accessConn, pgConn, stmt, tblName,
                                        counts, onLog, onProgress);
                            }
                            pgConn.commit();
                        }
                    }

                    log("Done. Rows processed: " + counts[0] + "  |  Rows uploaded: " + counts[1]);
                }
                return null;
            }

            private void log(String msg) {
                javafx.application.Platform.runLater(() -> onLog.accept(msg));
            }
        };
    }

    // ── External / Internal (same schema, different table & identifier suffix) ──

    private static void importExternalInternal(
            Connection accessConn, Connection pgConn, Statement stmt,
            String tblName, String examTypeLabel, String pgTable,
            int[] counts, Consumer<String> onLog, BiConsumer<Integer, Integer> onProgress) {

        String sql = "insert into " + pgTable +
                "(candidate_identifier, exam_type, schnum, sch_name, reg_no, cand_name, sex, d_of_b," +
                " subj1, grade1, subj2, grade2, subj3, grade3, subj4, grade4, subj5, grade5," +
                " subj6, grade6, subj7, grade7, subj8, grade8, subj9, grade9," +
                " n_of_sub, reason, debt, exam_year, show_photo, has_photo, show_dob," +
                " rem1, rem2, rem3, rem4, rem5, rem6, rem7, rem8, rem9, published)" +
                " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
                " on conflict(candidate_identifier) do nothing";

        try (ResultSet rs = stmt.executeQuery("select * from " + tblName)) {
            ResultSetMetaData meta = rs.getMetaData();
            boolean hasDob = hasColumn(meta, "D_OF_B");
            boolean hasSex = hasColumn(meta, "SEX");

            while (rs.next()) {
                counts[0]++;
                if (rs.getInt("N_OF_SUB") == 0) continue;

                try (PreparedStatement pst = pgConn.prepareStatement(sql)) {
                    pst.setString(1, rs.getString("REG_NO") + rs.getString("EXAM_YEAR") + examTypeLabel);
                    pst.setString(2, examTypeLabel);
                    pst.setString(3, rs.getString("SCHNUM"));
                    pst.setString(4, rs.getString("SCH_NAME"));
                    pst.setString(5, rs.getString("REG_NO"));
                    pst.setString(6, rs.getString("CAND_NAME"));
                    pst.setString(7, hasSex ? rs.getString("SEX") : "");
                    pst.setString(8, hasDob ? rs.getString("D_OF_B") : null);
                    setSubjectsGrades(pst, rs, 9, 9);
                    pst.setInt(27, (int) rs.getDouble("N_OF_SUB"));
                    pst.setString(28, rs.getString("REASON"));
                    pst.setString(29, rs.getString("DEBT"));
                    int yr = rs.getInt("EXAM_YEAR");
                    pst.setInt(30, yr);
                    pst.setBoolean(31, yr >= 2019);
                    pst.setBoolean(32, yr >= 2019);
                    pst.setBoolean(33, yr >= 2019);
                    setRemarks(pst, rs, 34, 9, false);
                    pst.setBoolean(43, false);
                    pst.execute();
                    counts[1]++;
                }
                javafx.application.Platform.runLater(() -> onProgress.accept(counts[0], counts[1]));
            }
        } catch (Exception e) {
            javafx.application.Platform.runLater(() -> onLog.accept("Error in table " + tblName + ": " + e.getMessage()));
        }
    }

    // ── BECE ──

    private static void importBece(
            Connection accessConn, Connection pgConn, Statement stmt,
            String tblName, int[] counts, Consumer<String> onLog, BiConsumer<Integer, Integer> onProgress) {

        String sql = "insert into results_bece(" +
                "candidate_identifier, exam_type, schnum, sch_name, reg_no, cand_name, sex, d_of_b," +
                " subj1, grade1, subj2, grade2, subj3, grade3, subj4, grade4, subj5, grade5," +
                " subj6, grade6, subj7, grade7, subj8, grade8, subj9, grade9," +
                " n_of_sub, reason, debt, exam_year, show_photo, has_photo, show_dob," +
                " rem1, rem2, rem3, rem4, rem5, rem6, rem7, rem8, rem9, published," +
                " subj10, grade10, subj11, grade11, subj12, grade12, rem10, rem11, rem12," +
                " serial_num, resmth, reseng)" +
                " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
                " on conflict(candidate_identifier) do nothing";

        try {
            ResultSet countRs = stmt.executeQuery("select count(*) as cnt from " + tblName);
            countRs.next();
            int total = countRs.getInt("cnt");
            javafx.application.Platform.runLater(() -> onLog.accept("  Rows in table: " + total));
        } catch (Exception ignored) {}

        try (ResultSet rs = stmt.executeQuery("select * from " + tblName)) {
            ResultSetMetaData meta = rs.getMetaData();
            boolean hasSerNo = hasColumn(meta, "SER_NO");

            while (rs.next()) {
                counts[0]++;
                if ((int) rs.getDouble("N_OF_SUB") == 0) continue;

                try (PreparedStatement pst = pgConn.prepareStatement(sql)) {
                    pst.setString(1, rs.getString("EXAM_NUM") + rs.getString("EXAM_YEAR") + "BECE");
                    pst.setString(2, "BECE");
                    pst.setString(3, rs.getString("SCHNUM"));
                    pst.setString(4, rs.getString("SCH_NAME"));
                    pst.setString(5, rs.getString("EXAM_NUM"));
                    pst.setString(6, rs.getString("CAND_NAME"));
                    pst.setString(7, rs.getString("SEX"));
                    pst.setString(8, rs.getString("D_OF_B"));
                    setSubjectsGrades(pst, rs, 9, 9);
                    pst.setInt(27, (int) rs.getDouble("N_OF_SUB"));
                    pst.setString(28, rs.getString("REASON"));
                    pst.setString(29, rs.getString("DEBT"));
                    int yr = rs.getInt("EXAM_YEAR");
                    pst.setInt(30, yr);
                    pst.setBoolean(31, yr >= 2019);
                    pst.setBoolean(32, yr >= 2019);
                    pst.setBoolean(33, yr >= 2019);
                    setRemarks(pst, rs, 34, 9, true);
                    pst.setBoolean(43, false);
                    // subj10-12
                    pst.setString(44, rs.getString("SUBJ10"));
                    pst.setString(45, rs.getString("GRADE10"));
                    pst.setString(46, rs.getString("SUBJ11"));
                    pst.setString(47, rs.getString("GRADE11"));
                    pst.setString(48, rs.getString("SUBJ12"));
                    pst.setString(49, rs.getString("GRADE12"));
                    pst.setString(50, remarkCalculatorBECE(rs.getString("GRADE10")));
                    pst.setString(51, remarkCalculatorBECE(rs.getString("GRADE11")));
                    pst.setString(52, remarkCalculatorBECE(rs.getString("GRADE12")));
                    pst.setObject(53, hasSerNo ? rs.getObject("SER_NO") : null);
                    pst.setString(54, rs.getString("resmth"));
                    pst.setString(55, rs.getString("reseng"));
                    pst.execute();
                    counts[1]++;
                }
                javafx.application.Platform.runLater(() -> onProgress.accept(counts[0], counts[1]));
            }
        } catch (Exception e) {
            javafx.application.Platform.runLater(() -> onLog.accept("Error in table " + tblName + ": " + e.getMessage()));
        }
    }

    // ── NCEE ──

    private static void importNcee(
            Connection accessConn, Connection pgConn, Statement stmt,
            String tblName, int[] counts, Consumer<String> onLog, BiConsumer<Integer, Integer> onProgress) {

        String sql = "insert into results_ncee(" +
                "candidate_identifier, cand_name, age, state_name, centre_name," +
                " maths, eng, quant, verbal, total, sex, state_of_origin," +
                " school1, school2, school3, school4, school5, school6, exam_no, exam_year)" +
                " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
                " on conflict(candidate_identifier) do nothing";

        try (ResultSet rs = stmt.executeQuery("select * from " + tblName)) {
            while (rs.next()) {
                counts[0]++;
                try (PreparedStatement pst = pgConn.prepareStatement(sql)) {
                    pst.setString(1, rs.getString("REG_NO") + "2025NCEE");
                    pst.setString(2, rs.getString("CAND_NAME"));
                    pst.setInt(3, rs.getInt("AGE"));
                    pst.setString(4, rs.getString("STATE_NAME"));
                    pst.setString(5, rs.getString("CENT_NAME"));
                    pst.setInt(6, rs.getInt("MATHS"));
                    pst.setInt(7, rs.getInt("ENG"));
                    pst.setInt(8, rs.getInt("QUANT"));
                    pst.setInt(9, rs.getInt("VERBAL"));
                    String total = rs.getString("TS_TOTAL").trim();
                    pst.setInt(10, total.equalsIgnoreCase("XX") ? -100 : (int) Double.parseDouble(total));
                    pst.setString(11, rs.getString("SEX"));
                    String soo = rs.getString("S_OF_ONAME");
                    pst.setString(12, soo == null ? "" : soo);
                    pst.setString(13, rs.getString("SCHOOL1"));
                    pst.setString(14, rs.getString("SCHOOL2"));
                    pst.setString(15, rs.getString("SCHOOL3"));
                    pst.setString(16, rs.getString("SCHOOL4"));
                    pst.setString(17, rs.getString("SCHOOL5"));
                    pst.setString(18, rs.getString("SCHOOL6"));
                    pst.setString(19, rs.getString("REG_NO"));
                    pst.setInt(20, 2025);
                    pst.execute();
                    counts[1]++;
                }
                javafx.application.Platform.runLater(() -> onProgress.accept(counts[0], counts[1]));
            }
        } catch (Exception e) {
            javafx.application.Platform.runLater(() -> onLog.accept("Error in table " + tblName + ": " + e.getMessage()));
        }
    }

    // ── GIFTED ──

    private static void importGifted(
            Connection accessConn, Connection pgConn, Statement stmt,
            String tblName, int[] counts, Consumer<String> onLog, BiConsumer<Integer, Integer> onProgress) {

        String sql = "insert into results_gifted(" +
                "candidate_identifier, cand_name, age, state_name, centre_name," +
                " maths, eng, quant, verbal, total, sex, school1, exam_no, exam_year, remark)" +
                " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
                " on conflict(candidate_identifier) do nothing";

        try (ResultSet rs = stmt.executeQuery("select * from " + tblName)) {
            while (rs.next()) {
                counts[0]++;
                try (PreparedStatement pst = pgConn.prepareStatement(sql)) {
                    pst.setString(1, rs.getString("REG_NO") + rs.getInt("exam_year") + "GIFTED");
                    pst.setString(2, rs.getString("CAND_NAME"));
                    pst.setInt(3, rs.getInt("AGE"));
                    pst.setString(4, rs.getString("STATE_NAME"));
                    pst.setString(5, rs.getString("CENT_NAME"));
                    pst.setInt(6, rs.getInt("MATHS"));
                    pst.setInt(7, rs.getInt("ENG"));
                    pst.setInt(8, rs.getInt("QUANT"));
                    pst.setInt(9, rs.getInt("VERBAL"));
                    pst.setInt(10, rs.getInt("TS_TOTAL"));
                    pst.setString(11, rs.getString("SEX"));
                    pst.setString(12, rs.getString("SCHOOL1"));
                    pst.setString(13, rs.getString("REG_NO"));
                    pst.setInt(14, rs.getInt("exam_year"));
                    pst.setString(15, rs.getString("REMARKS"));
                    pst.execute();
                    counts[1]++;
                }
                javafx.application.Platform.runLater(() -> onProgress.accept(counts[0], counts[1]));
            }
        } catch (Exception e) {
            javafx.application.Platform.runLater(() -> onLog.accept("Error in table " + tblName + ": " + e.getMessage()));
        }
    }

    // ── Helpers ──

    private static void setSubjectsGrades(PreparedStatement pst, ResultSet rs, int startIdx, int count)
            throws SQLException {
        for (int i = 1; i <= count; i++) {
            pst.setString(startIdx++, rs.getString("SUBJ" + i));
            pst.setString(startIdx++, rs.getString("GRADE" + i));
        }
    }

    private static void setRemarks(PreparedStatement pst, ResultSet rs, int startIdx, int count, boolean bece)
            throws SQLException {
        for (int i = 1; i <= count; i++) {
            String grade = rs.getString("GRADE" + i);
            pst.setString(startIdx++, bece ? remarkCalculatorBECE(grade) : remarkCalculator(grade));
        }
    }

    private static boolean hasColumn(ResultSetMetaData meta, String name) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnName(i).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static String remarkCalculator(String grade) {
        if (grade == null) return "";
        if (grade.toUpperCase().contains("C")) return "CREDIT";
        return switch (grade.toUpperCase()) {
            case "A1" -> "EXCELLENT";
            case "B2" -> "VERY GOOD";
            case "B3" -> "GOOD";
            case "D7", "E8" -> "PASS";
            case "F9" -> "FAIL";
            case "*"  -> "PENDING";
            case "**" -> "ABSENT";
            case "X"  -> "CANCELLED";
            case "NR" -> "NO RESULT";
            default   -> "";
        };
    }

    public static String remarkCalculatorBECE(String grade) {
        if (grade == null) return "";
        if (grade.toUpperCase().contains("C")) return "LOWER CREDIT";
        return switch (grade.toUpperCase()) {
            case "A" -> "EXCELLENT";
            case "B" -> "UPPER CREDIT";
            case "P" -> "PASS";
            case "F" -> "FAIL";
            default  -> "";
        };
    }
}
