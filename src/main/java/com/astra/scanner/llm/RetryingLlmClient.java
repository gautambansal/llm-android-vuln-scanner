package com.astra.scanner.llm;

/**
 * Decorator: retries rate-limit / overload errors with long, visible waits.
 * SDK retries are short and silent; free tiers need waits of 10-60s, and we want to SEE them in the log.
 */
public final class RetryingLlmClient implements LlmClient {

    private final LlmClient inner;
    private final int attempts;
    private final long baseWaitMs;

    public RetryingLlmClient(LlmClient inner, int attempts, long baseWaitMs) {
        this.inner = inner;
        this.attempts = attempts;
        this.baseWaitMs = baseWaitMs;
    }

    @Override public String name() { return inner.name(); }

    @Override
    public LlmResponse complete(String system, String user) throws LlmException {
        for (int attempt = 1; ; attempt++) {
            try {
                return inner.complete(system, user);
            } catch (LlmException e) {
                if (!e.retryable() || attempt >= attempts) throw e;
                long wait = baseWaitMs * (1L << (attempt - 1)); // 15s, 30s, 60s ...
                System.err.printf("  ~ %s: retry %d/%d in %ds (%s)%n", name(), attempt, attempts - 1, wait / 1000,
                        e.getMessage().length() > 120 ? e.getMessage().substring(0, 120) + "..." : e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
