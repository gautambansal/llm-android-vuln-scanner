package com.astra.scanner.llm;

/** What every provider returns, normalised. Tokens + latency feed the cost/latency metrics. */
public record LlmResponse(String text, String model, long inputTokens, long outputTokens, double latencySeconds) {}
