package com.readaccess.app;

public class ApiConfig {
    private String endpointUrl = "https://result.api.neco.gov.ng/api/result-upload/";
    private int batchSize = 1000;
    private int connectionTimeoutSeconds = 30;
    private int readTimeoutSeconds = 60;
    private int maxRetries = 3;
    private int parallelThreads = 8;

    public ApiConfig() {}

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
    public void setConnectionTimeoutSeconds(int v) { this.connectionTimeoutSeconds = v; }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getParallelThreads() { return parallelThreads; }
    public void setParallelThreads(int parallelThreads) { this.parallelThreads = parallelThreads; }
}
