package com.readaccess.app;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.DriverManager;

public class ConfigController {

    @FXML private TextField tfHost, tfPort, tfDatabase, tfUsername;
    @FXML private PasswordField pfPassword;
    @FXML private Button btnTest, btnSave, btnCancel;
    @FXML private Label lblTestResult;

    private boolean saved = false;
    private DbConfig result;

    public void populate(DbConfig config) {
        tfHost.setText(config.getHost());
        tfPort.setText(String.valueOf(config.getPort()));
        tfDatabase.setText(config.getDatabase());
        tfUsername.setText(config.getUsername());
        pfPassword.setText(config.getPassword());
        lblTestResult.setText("");
    }

    @FXML
    private void onTestConnection() {
        lblTestResult.setText("Testing…");
        lblTestResult.getStyleClass().removeAll("test-ok", "test-fail");

        DbConfig cfg = buildConfig();
        if (cfg == null) return;

        Thread t = new Thread(() -> {
            try {
                Connection conn = DriverManager.getConnection(cfg.getJdbcUrl(), cfg.getUsername(), cfg.getPassword());
                conn.close();
                javafx.application.Platform.runLater(() -> {
                    lblTestResult.setText("Connection successful!");
                    lblTestResult.getStyleClass().add("test-ok");
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    lblTestResult.setText("Failed: " + e.getMessage());
                    lblTestResult.getStyleClass().add("test-fail");
                });
            }
        }, "test-connection");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onSave() {
        DbConfig cfg = buildConfig();
        if (cfg == null) return;
        result = cfg;
        saved = true;
        closeWindow();
    }

    @FXML
    private void onCancel() {
        closeWindow();
    }

    private DbConfig buildConfig() {
        String host = tfHost.getText().trim();
        String portText = tfPort.getText().trim();
        String db = tfDatabase.getText().trim();
        String user = tfUsername.getText().trim();
        String pass = pfPassword.getText();

        if (host.isEmpty() || db.isEmpty() || user.isEmpty()) {
            lblTestResult.setText("Host, database, and username are required.");
            lblTestResult.getStyleClass().removeAll("test-ok", "test-fail");
            lblTestResult.getStyleClass().add("test-fail");
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            lblTestResult.setText("Port must be a number.");
            lblTestResult.getStyleClass().add("test-fail");
            return null;
        }

        return new DbConfig(host, port, db, user, pass);
    }

    private void closeWindow() {
        ((Stage) btnCancel.getScene().getWindow()).close();
    }

    public boolean isSaved() { return saved; }
    public DbConfig getConfig() { return result; }
}
