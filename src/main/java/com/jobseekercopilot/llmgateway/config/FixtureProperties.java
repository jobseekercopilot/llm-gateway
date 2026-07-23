package com.jobseekercopilot.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fixture")
public class FixtureProperties {
    private String systemDataServiceUrl = "http://localhost:8103";
    private String datasetId = "uk-software-developer-demo";
    private String datasetVersion = "1.0.0";
    private String scenario = "happy-path";

    public String getSystemDataServiceUrl() {
        return systemDataServiceUrl;
    }

    public void setSystemDataServiceUrl(String systemDataServiceUrl) {
        this.systemDataServiceUrl = systemDataServiceUrl;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }
}
