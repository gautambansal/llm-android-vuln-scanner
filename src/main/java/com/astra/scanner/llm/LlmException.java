package com.astra.scanner.llm;

/** Any failure talking to a model. `retryable` = worth trying again later (429 rate limit, 5xx, timeout). */
public class LlmException extends Exception {

    private final boolean retryable;

    public LlmException(String message) { this(message, null, false); }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }

    /** HTTP status codes worth retrying: rate limited, overloaded, server errors. */
    public static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /** The model declined the request (e.g. a safety classifier on security content). */
    public static class Refusal extends LlmException {
        public Refusal(String message) { super(message); }
    }
}
