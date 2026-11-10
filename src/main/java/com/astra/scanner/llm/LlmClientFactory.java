package com.astra.scanner.llm;

import com.astra.scanner.config.AppConfig;
import com.astra.scanner.config.ModelConfig;

import java.util.Map;
import java.util.function.Function;

/**
 * Maps the `provider` field in config.yaml to a client implementation.
 * A new provider that speaks the OpenAI API needs NO code: just a config entry.
 * A provider with its own API needs one new class + one line in PROVIDERS.
 */
public final class LlmClientFactory {

    @FunctionalInterface
    private interface Constructor {
        LlmClient create(String name, ModelConfig cfg, String apiKey);
    }

    private static final Map<String, Constructor> PROVIDERS = Map.of(
            "anthropic", AnthropicLlmClient::new,
            "openai_compatible", OpenAiCompatibleLlmClient::new
    );

    private final AppConfig config;
    private final Function<String, String> env; // where API keys come from (env vars / .env)

    public LlmClientFactory(AppConfig config, Function<String, String> env) {
        this.config = config;
        this.env = env;
    }

    public LlmClient create(String modelName) throws LlmException {
        ModelConfig cfg = config.models().get(modelName);
        if (cfg == null) throw new LlmException("unknown model '" + modelName + "'");

        Constructor ctor = PROVIDERS.get(cfg.provider());
        if (ctor == null) {
            throw new LlmException("unknown provider '" + cfg.provider() + "' for model '" + modelName
                    + "'. Known: " + PROVIDERS.keySet());
        }
        String key = env.apply(cfg.apiKeyEnv());
        if (key == null || key.isBlank()) {
            throw new LlmException("[" + modelName + "] env var " + cfg.apiKeyEnv() + " is not set");
        }
        // Decorators, innermost first: provider client -> rate limiter -> retry.
        // (Retry outside the limiter, so every retry also waits for budget.)
        LlmClient client = ctor.create(modelName, cfg, key);
        if (cfg.requestsPerMinute() != null || cfg.tokensPerMinute() != null) {
            client = new RateLimitedLlmClient(client, cfg.requestsPerMinute(), cfg.tokensPerMinute(), cfg.maxTokens());
        }
        if (cfg.retryAttempts() > 1) {
            client = new RetryingLlmClient(client, cfg.retryAttempts(), cfg.retryBaseWaitSeconds() * 1000L);
        }
        return client;
    }

    public LlmClient forRole(String role) throws LlmException {
        return create(config.modelNameFor(role));
    }
}
