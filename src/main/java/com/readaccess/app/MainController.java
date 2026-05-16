package com.readaccess.app;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import java.util.Arrays;

import java.io.File;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController implements Initializable {

    @FXML private Label fileLabel;
    @FXML private HBox dropZone;
    @FXML private ComboBox<String> cbExamType;
    @FXML private Button btnImport, btnCancel, btnConfigure, btnClearLog;
    @FXML private ProgressBar progressBar;
    @FXML private Label lblProcessed, lblUploaded, lblStatus;
    @FXML private TextArea logArea;
    @FXML private Label lblDbInfo;
    @FXML private UploadController uploadTabController;

    private File selectedFile;
    private DbConfig dbConfig;
    private Task<Void> currentTask;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-thread");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        dbConfig = ConfigStore.load();
        updateDbInfoLabel();
        btnImport.setDisable(true);
        btnCancel.setDisable(true);
        progressBar.setProgress(0);

        // Populate exam type dropdown
        Arrays.stream(ImportService.ExamType.values())
              .forEach(e -> cbExamType.getItems().add(e.getLabel()));
        cbExamType.valueProperty().addListener((obs, o, n) -> updateImportButton());

        // Wire drag-and-drop on the drop zone
        dropZone.setOnDragOver(this::onDragOver);
        dropZone.setOnDragDropped(this::onDragDropped);
    }

    // ── File selection ──

    @FXML
    private void onBrowseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select MS Access Database File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Access Files", "*.accdb", "*.mdb"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        File f = chooser.showOpenDialog(dropZone.getScene().getWindow());
        if (f != null) setFile(f);
    }

    private void onDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            File f = db.getFiles().get(0);
            if (f.getName().toLowerCase().endsWith(".accdb") || f.getName().toLowerCase().endsWith(".mdb")) {
                setFile(f);
                event.setDropCompleted(true);
            } else {
                appendLog("Unsupported file type. Please drop an .accdb or .mdb file.");
                event.setDropCompleted(false);
            }
        }
        event.consume();
    }

    private void setFile(File f) {
        selectedFile = f;
        fileLabel.setText(f.getName());
        fileLabel.setStyle("-fx-text-fill: #e2e8f0;");
        dropZone.getStyleClass().remove("drop-zone-empty");
        dropZone.getStyleClass().add("drop-zone-filled");
        appendLog("File selected: " + f.getAbsolutePath());
        updateImportButton();
    }

    // ── Import ──

    @FXML
    private void onImport() {
        if (selectedFile == null || cbExamType.getValue() == null) return;

        ImportService.ExamType examType = Arrays.stream(ImportService.ExamType.values())
                .filter(e -> e.getLabel().equals(cbExamType.getValue()))
                .findFirst().orElseThrow();

        appendLog("─────────────────────────────────────────");
        appendLog("Starting import | Exam: " + examType.getLabel() + " | File: " + selectedFile.getName());

        btnImport.setDisable(true);
        btnCancel.setDisable(false);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        lblStatus.setText("Importing…");
        lblProcessed.setText("0");
        lblUploaded.setText("0");

        currentTask = ImportService.createTask(
                selectedFile.getAbsolutePath(),
                examType,
                dbConfig,
                msg -> appendLog(msg),
                (processed, uploaded) -> {
                    lblProcessed.setText(String.valueOf(processed));
                    lblUploaded.setText(String.valueOf(uploaded));
                });

        currentTask.setOnSucceeded(e -> onTaskDone(true, null));
        currentTask.setOnFailed(e -> onTaskDone(false, currentTask.getException()));
        currentTask.setOnCancelled(e -> onTaskDone(false, null));

        executor.submit(currentTask);
    }

    @FXML
    private void onCancel() {
        if (currentTask != null) currentTask.cancel();
    }

    private void onTaskDone(boolean success, Throwable err) {
        Platform.runLater(() -> {
            btnImport.setDisable(false);
            btnCancel.setDisable(true);
            progressBar.setProgress(success ? 1.0 : 0);
            if (success) {
                lblStatus.setText("Completed successfully");
                appendLog("Import finished successfully.");
            } else if (err != null) {
                lblStatus.setText("Failed: " + err.getMessage());
                appendLog("Import failed: " + err.getMessage());
                if (err.getCause() != null) appendLog("Cause: " + err.getCause().getMessage());
            } else {
                lblStatus.setText("Cancelled");
            }
        });
    }

    // ── Configuration ──

    @FXML
    private void onConfigure() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/readaccess/app/ConfigView.fxml"));
            Scene scene = new Scene(loader.load(), 500, 480);
            scene.getStylesheets().add(getClass().getResource("/com/readaccess/app/styles.css").toExternalForm());

            ConfigController ctrl = loader.getController();
            ctrl.populate(dbConfig);

            Stage dialog = new Stage();
            dialog.setTitle("Database Configuration");
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(btnConfigure.getScene().getWindow());
            dialog.setScene(scene);
            dialog.setResizable(false);
            dialog.showAndWait();

            if (ctrl.isSaved()) {
                dbConfig = ctrl.getConfig();
                ConfigStore.save(dbConfig);
                updateDbInfoLabel();
                appendLog("Database configuration updated.");
                if (uploadTabController != null) uploadTabController.refreshDbConfig(dbConfig);
            }
        } catch (Exception e) {
            appendLog("Could not open config dialog: " + e.getMessage());
        }
    }

    @FXML
    private void onClearLog() {
        logArea.clear();
    }

    // ── Helpers ──

    private void updateImportButton() {
        btnImport.setDisable(selectedFile == null || cbExamType.getValue() == null);
    }

    private void appendLog(String msg) {
        String timestamp = "[" + LocalTime.now().format(TIME_FMT) + "] ";
        Platform.runLater(() -> {
            logArea.appendText(timestamp + msg + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void updateDbInfoLabel() {
        lblDbInfo.setText(dbConfig.getHost() + ":" + dbConfig.getPort() + " / " + dbConfig.getDatabase());
    }
}
