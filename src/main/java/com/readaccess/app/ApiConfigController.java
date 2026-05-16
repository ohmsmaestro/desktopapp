package com.readaccess.app;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

public class ApiConfigController {

    @FXML private TextField tfEndpointUrl, tfBatchSize, tfConnTimeout, tfReadTimeout, tfMaxRetries, tfParallelThreads;
    @FXML private Button btnSave, btnCancel;
    @FXML private Label lblError;

    private boolean saved = false;
    private ApiConfig result;

    public void populate(ApiConfig config) {
        tfEndpointUrl.setText(config.getEndpointUrl());
        tfBatchSize.setText(String.valueOf(config.getBatchSize()));
        tfConnTimeout.setText(String.valueOf(config.getConnectionTimeoutSeconds()));
        tfReadTimeout.setText(String.valueOf(config.getReadTimeoutSeconds()));
        tfMaxRetries.setText(String.valueOf(config.getMaxRetries()));
        tfParallelThreads.setText(String.valueOf(config.getParallelThreads()));
        lblError.setText("");
    }

    @FXML
    private void onSave() {
        try {
            String url = tfEndpointUrl.getText().trim();
            if (url.isEmpty()) { showError("Endpoint URL is required."); return; }
            if (!url.endsWith("/")) url += "/";

            ApiConfig cfg = new ApiConfig();
            cfg.setEndpointUrl(url);
            cfg.setBatchSize(Integer.parseInt(tfBatchSize.getText().trim()));
            cfg.setConnectionTimeoutSeconds(Integer.parseInt(tfConnTimeout.getText().trim()));
            cfg.setReadTimeoutSeconds(Integer.parseInt(tfReadTimeout.getText().trim()));
            cfg.setMaxRetries(Integer.parseInt(tfMaxRetries.getText().trim()));
            int threads = Integer.parseInt(tfParallelThreads.getText().trim());
            if (threads < 1 || threads > 64) { showError("Parallel threads must be between 1 and 64."); return; }
            cfg.setParallelThreads(threads);

            result = cfg;
            saved = true;
            close();
        } catch (NumberFormatException e) {
            showError("Batch size, timeouts, and retries must be whole numbers.");
        }
    }

    @FXML
    private void onCancel() { close(); }

    private void showError(String msg) { lblError.setText(msg); }
    private void close() { ((Stage) btnCancel.getScene().getWindow()).close(); }

    public boolean isSaved() { return saved; }
    public ApiConfig getConfig() { return result; }
}
