package com.readaccess.app;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Graphics2D;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UploadController implements Initializable {

    @FXML private ComboBox<String> cbUploadExamType;
    @FXML private ComboBox<Integer> cbYear;
    @FXML private Button btnUpload, btnCancelUpload, btnApiConfig, btnClearUploadLog, btnResetSent, btnScrollLock;
    @FXML private ProgressBar uploadProgressBar;
    @FXML private Label lblUpProcessed, lblUpSent, lblUpFailed, lblUpStatus, lblApiInfo;
    @FXML private TextArea uploadLogArea;

    private DbConfig dbConfig;
    private ApiConfig apiConfig;
    private Task<Void> currentTask;
    private PrintWriter logWriter;
    private boolean autoScroll = true;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "upload-thread");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        dbConfig = ConfigStore.load();
        apiConfig = ConfigStore.loadApi();
        updateApiInfoLabel();
        btnUpload.setDisable(true);
        btnCancelUpload.setDisable(true);
        uploadProgressBar.setProgress(0);

        // Exam type dropdown
        Arrays.stream(ImportService.ExamType.values())
              .forEach(e -> cbUploadExamType.getItems().add(e.getLabel()));
        cbUploadExamType.valueProperty().addListener((obs, o, n) -> updateButtons());

        // Year dropdown: 2023 → current year
        int currentYear = Year.now().getValue();
        for (int y = currentYear; y >= 2023; y--) cbYear.getItems().add(y);
        cbYear.getSelectionModel().selectFirst();
        cbYear.valueProperty().addListener((obs, o, n) -> updateButtons());
    }

    /** Called by MainController after it reloads db config. */
    public void refreshDbConfig(DbConfig config) {
        this.dbConfig = config;
    }

    @FXML
    private void onUpload() {
        ImportService.ExamType examType = Arrays.stream(ImportService.ExamType.values())
                .filter(e -> e.getLabel().equals(cbUploadExamType.getValue()))
                .findFirst().orElseThrow();
        int year = cbYear.getValue();

        openLogFile();
        appendLog("─────────────────────────────────────────");
        appendLog("Starting upload | Exam: " + examType.getLabel() + " | Year: " + year);

        btnUpload.setDisable(true);
        btnCancelUpload.setDisable(false);
        uploadProgressBar.setProgress(0);
        lblUpStatus.setText("Uploading…");
        lblUpProcessed.setText("0");
        lblUpSent.setText("0");
        lblUpFailed.setText("0");

        currentTask = UploadService.createTask(
                examType, year, dbConfig, apiConfig,
                msg -> appendLog(msg),
                (processed, sent, failed) -> {
                    lblUpProcessed.setText(String.valueOf(processed));
                    lblUpSent.setText(String.valueOf(sent));
                    lblUpFailed.setText(String.valueOf(failed));
                });

        uploadProgressBar.progressProperty().bind(currentTask.progressProperty());
        currentTask.setOnSucceeded(e -> { uploadProgressBar.progressProperty().unbind(); onTaskDone(true, null); });
        currentTask.setOnFailed(e -> { uploadProgressBar.progressProperty().unbind(); onTaskDone(false, currentTask.getException()); });
        currentTask.setOnCancelled(e -> { uploadProgressBar.progressProperty().unbind(); onTaskDone(false, null); });
        executor.submit(currentTask);
    }

    @FXML
    private void onCancelUpload() {
        if (currentTask != null) currentTask.cancel();
    }

    @FXML
    private void onResetSent() {
        ImportService.ExamType examType = Arrays.stream(ImportService.ExamType.values())
                .filter(e -> e.getLabel().equals(cbUploadExamType.getValue()))
                .findFirst().orElseThrow();
        int year = cbYear.getValue();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset Sent Flag");
        confirm.setHeaderText("Reset all sent records for " + examType.getLabel() + " — " + year + "?");
        confirm.setContentText("This will mark every sent record as unsent so they will be re-uploaded on the next run.");
        confirm.initOwner(btnResetSent.getScene().getWindow());
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                executor.submit(() -> {
                    try (java.sql.Connection pg = java.sql.DriverManager.getConnection(
                            dbConfig.getJdbcUrl(), dbConfig.getUsername(), dbConfig.getPassword());
                         java.sql.PreparedStatement pst = pg.prepareStatement(
                                 "UPDATE " + tableFor(examType) + " SET sent = false WHERE exam_year = ?")) {
                        pst.setInt(1, year);
                        int rows = pst.executeUpdate();
                        appendLog("Reset sent flag for " + rows + " records (" + examType.getLabel() + " " + year + ").");
                    } catch (Exception e) {
                        appendLog("Reset failed: " + e.getMessage());
                    }
                });
            }
        });
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

    private void onTaskDone(boolean success, Throwable err) {
        Platform.runLater(() -> {
            btnUpload.setDisable(false);
            btnCancelUpload.setDisable(true);
            uploadProgressBar.setProgress(success ? 1.0 : 0);
            if (success) {
                lblUpStatus.setText("Completed");
                appendLog("Upload finished successfully.");
                sendTrayNotification("Upload Complete", "Records sent successfully.");
            } else if (err != null) {
                lblUpStatus.setText("Failed");
                appendLog("Upload failed: " + err.getMessage());
                sendTrayNotification("Upload Failed", err.getMessage());
            } else {
                lblUpStatus.setText("Cancelled");
            }
            closeLogFile();
        });
    }

    @FXML
    private void onApiConfig() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/readaccess/app/ApiConfigView.fxml"));
            Scene scene = new Scene(loader.load(), 580, 420);
            scene.getStylesheets().add(getClass().getResource("/com/readaccess/app/styles.css").toExternalForm());

            ApiConfigController ctrl = loader.getController();
            ctrl.populate(apiConfig);

            Stage dialog = new Stage();
            dialog.setTitle("API Configuration");
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(btnApiConfig.getScene().getWindow());
            dialog.setScene(scene);
            dialog.setResizable(false);
            dialog.showAndWait();

            if (ctrl.isSaved()) {
                apiConfig = ctrl.getConfig();
                ConfigStore.saveApi(apiConfig);
                updateApiInfoLabel();
                appendLog("API configuration updated.");
            }
        } catch (Exception e) {
            appendLog("Could not open API config: " + e.getMessage());
        }
    }

    @FXML
    private void onClearUploadLog() {
        uploadLogArea.clear();
    }

    @FXML
    private void onScrollLock() {
        autoScroll = !autoScroll;
        btnScrollLock.setText(autoScroll ? "Lock Scroll" : "Unlock Scroll");
        if (autoScroll) uploadLogArea.setScrollTop(Double.MAX_VALUE);
    }

    private void updateButtons() {
        boolean ready = cbUploadExamType.getValue() != null && cbYear.getValue() != null;
        btnUpload.setDisable(!ready);
        btnResetSent.setDisable(!ready);
    }

    private void appendLog(String msg) {
        String ts = "[" + LocalTime.now().format(TIME_FMT) + "] ";
        String line = ts + msg;
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
        Platform.runLater(() -> {
            uploadLogArea.appendText(line + "\n");
            if (autoScroll) uploadLogArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void openLogFile() {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".readaccess", "logs");
            Files.createDirectories(dir);
            String name = "upload-" + LocalDate.now().format(DATE_FMT) + ".log";
            // append so multiple runs on the same day accumulate in one file
            logWriter = new PrintWriter(new FileWriter(dir.resolve(name).toFile(), true));
        } catch (IOException e) {
            // non-fatal — UI log still works
        }
    }

    private void closeLogFile() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    private void sendTrayNotification(String title, String message) {
        if (!SystemTray.isSupported()) return;
        new Thread(() -> {
            try {
                // Build a small sky-blue circle icon programmatically
                BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new java.awt.Color(56, 189, 248));
                g.fillOval(0, 0, 16, 16);
                g.dispose();

                SystemTray tray = SystemTray.getSystemTray();
                TrayIcon icon = new TrayIcon(img, "ReadAccess Exam");
                icon.setImageAutoSize(true);
                tray.add(icon);
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
                Thread.sleep(6000);
                tray.remove(icon);
            } catch (Exception ignored) { /* non-fatal */ }
        }, "tray-notify").start();
    }

    private void updateApiInfoLabel() {
        String url = apiConfig.getEndpointUrl();
        lblApiInfo.setText(url.length() > 45 ? url.substring(0, 45) + "…" : url);
    }
}
