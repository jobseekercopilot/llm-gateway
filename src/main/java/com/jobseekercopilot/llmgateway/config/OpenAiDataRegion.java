package com.jobseekercopilot.llmgateway.config;

public enum OpenAiDataRegion {
    GLOBAL("api.openai.com", false),
    UNITED_STATES("us.api.openai.com", false),
    EUROPE("eu.api.openai.com", true),
    AUSTRALIA("au.api.openai.com", true),
    CANADA("ca.api.openai.com", true),
    JAPAN("jp.api.openai.com", true),
    INDIA("in.api.openai.com", true),
    SINGAPORE("sg.api.openai.com", true),
    SOUTH_KOREA("kr.api.openai.com", true),
    UNITED_KINGDOM("gb.api.openai.com", true),
    UNITED_ARAB_EMIRATES("ae.api.openai.com", true);

    private final String endpointHost;
    private final boolean enhancedDataControlsRequired;

    OpenAiDataRegion(String endpointHost, boolean enhancedDataControlsRequired) {
        this.endpointHost = endpointHost;
        this.enhancedDataControlsRequired = enhancedDataControlsRequired;
    }

    public String endpointHost() {
        return endpointHost;
    }

    public boolean enhancedDataControlsRequired() {
        return enhancedDataControlsRequired;
    }
}
