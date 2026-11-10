package com.astra.scanner.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One named model entry from config.yaml.
 * Provider-specific fields (baseUrl, refusalFallback, reasoningEffort, jsonMode) are optional
 * and ignored by providers that don't use them.
 */
@JsonIgnoreProperties(ignoreUnknown = false) // a typo in config.yaml fails fast at startup
public record ModelConfig(
        String provider,
        String model,
        String apiKeyEnv,
        String baseUrl,
        Long maxTokens,
        Double temperature,
        Integer maxRetries,          // SDK's own (short, silent) retries
        Integer timeoutSeconds,
        Boolean refusalFallback,
        Integer requestsPerMinute,   // client-side rate limits; null = unlimited
        Integer tokensPerMinute,
        Integer retryAttempts,       // our own visible retries for 429/5xx, with long waits
        Integer retryBaseWaitSeconds,
        Integer maxChunkTokens,      // how much code this model reads per call; null = ingest default
        String reasoningEffort,      // for reasoning models (gpt-oss): low | medium | high
        Boolean jsonMode             // ask the API for a JSON object response, if supported
) {
    public ModelConfig {
        if (provider == null || model == null || apiKeyEnv == null) {
            throw new IllegalArgumentException("model entry needs provider, model and apiKeyEnv");
        }
        if (maxTokens == null) maxTokens = 8000L;
        if (maxRetries == null) maxRetries = 1;
        if (timeoutSeconds == null) timeoutSeconds = 300;
        if (refusalFallback == null) refusalFallback = false;
        if (retryAttempts == null) retryAttempts = 4;
        if (retryBaseWaitSeconds == null) retryBaseWaitSeconds = 15;
        if (jsonMode == null) jsonMode = false;
    }
}
