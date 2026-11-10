package com.astra.scanner.llm;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Decorator: keeps any LlmClient under a requests-per-minute and/or tokens-per-minute budget,
 * so we wait politely instead of hammering the API into 429 errors.
 * Sliding 60-second window; each call's tokens are estimated as (prompt chars / 4) + maxTokens.
 */
public final class RateLimitedLlmClient implements LlmClient {

    private static final long WINDOW_MS = 60_000;

    private final LlmClient inner;
    private final Integer requestsPerMinute; // null = no limit
    private final Integer tokensPerMinute;   // null = no limit
    private final long maxOutputTokens;
    private final Deque<long[]> window = new ArrayDeque<>(); // {timestampMs, tokens}

    public RateLimitedLlmClient(LlmClient inner, Integer requestsPerMinute, Integer tokensPerMinute, long maxOutputTokens) {
        this.inner = inner;
        this.requestsPerMinute = requestsPerMinute;
        this.tokensPerMinute = tokensPerMinute;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override public String name() { return inner.name(); }

    @Override
    public LlmResponse complete(String system, String user) throws LlmException {
        long estimate = (system.length() + user.length()) / 4 + maxOutputTokens;
        reserve(estimate);
        return inner.complete(system, user);
    }

    private synchronized void reserve(long tokens) throws LlmException {
        while (true) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst()[0] > WINDOW_MS) window.pollFirst();
            long used = window.stream().mapToLong(e -> e[1]).sum();
            boolean requestsOk = requestsPerMinute == null || window.size() < requestsPerMinute;
            // A single request bigger than the whole token budget can never fit; let it through alone.
            boolean tokensOk = tokensPerMinute == null || used + tokens <= tokensPerMinute || window.isEmpty();
            if (requestsOk && tokensOk) {
                window.addLast(new long[]{now, tokens});
                return;
            }
            long waitMs = WINDOW_MS - (now - window.peekFirst()[0]) + 50;
            try {
                wait(waitMs); // releases the lock while waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("[" + name() + "] interrupted while waiting for rate limit");
            }
        }
    }
}
