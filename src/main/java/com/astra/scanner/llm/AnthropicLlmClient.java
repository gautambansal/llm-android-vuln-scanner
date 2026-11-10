package com.astra.scanner.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.astra.scanner.config.ModelConfig;

import java.time.Duration;
import java.util.stream.Collectors;

/** Claude via the official Anthropic Java SDK. */
public final class AnthropicLlmClient implements LlmClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String name;
    private final ModelConfig cfg;
    private final AnthropicClient client;

    public AnthropicLlmClient(String name, ModelConfig cfg, String apiKey) {
        this.name = name;
        this.cfg = cfg;
        // The SDK already retries 429 / 5xx / connection errors with backoff.
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(cfg.maxRetries())
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .build();
    }

    @Override public String name() { return name; }

    @Override
    public LlmResponse complete(String system, String user) throws LlmException {
        // We use the beta endpoint so we can opt into server-side refusal fallbacks:
        // if a safety classifier declines, the API retries on a fallback model in the same call.
        var params = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(cfg.maxTokens())
                .system(system)
                .addUserMessage(user);
        if (cfg.refusalFallback()) {
            params.addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault());
        }

        long start = System.nanoTime();
        BetaMessage resp;
        try {
            resp = client.beta().messages().create(params.build());
        } catch (AnthropicServiceException e) { // got an HTTP error response
            throw new LlmException("[" + name + "] HTTP " + e.statusCode() + ": " + OpenAiCompatibleLlmClient.shorten(e.getMessage()),
                    e, LlmException.isRetryableStatus(e.statusCode()));
        } catch (AnthropicException e) {        // network / timeout / parsing
            throw new LlmException("[" + name + "] " + e.getClass().getSimpleName() + ": " + e.getMessage(), e, true);
        }
        double latency = (System.nanoTime() - start) / 1e9;

        if (resp.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
            throw new LlmException.Refusal("[" + name + "] model refused the request");
        }

        String text = resp.content().stream()
                .filter(BetaContentBlock::isText)
                .map(b -> b.asText().text())
                .collect(Collectors.joining());

        return new LlmResponse(text, resp.model().asString(),
                resp.usage().inputTokens(), resp.usage().outputTokens(), latency);
    }
}
