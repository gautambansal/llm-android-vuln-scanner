package com.astra.scanner.llm;

import com.astra.scanner.config.ModelConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.time.Duration;

/**
 * Any provider that speaks the OpenAI chat-completions API: Groq, Gemini, OpenAI,
 * Together, a local vLLM/Ollama server... Only baseUrl + model + key differ.
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    static final java.util.regex.Pattern REASONING_BLOCK =
            java.util.regex.Pattern.compile("(?s)<(think|thought|thinking)>.*?</\\1>");

    private final String name;
    private final ModelConfig cfg;
    private final OpenAIClient client;

    public OpenAiCompatibleLlmClient(String name, ModelConfig cfg, String apiKey) {
        this.name = name;
        this.cfg = cfg;
        var builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(cfg.maxRetries())
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        if (cfg.baseUrl() != null) builder.baseUrl(cfg.baseUrl()); // null = api.openai.com
        this.client = builder.build();
    }

    @Override public String name() { return name; }

    @Override
    public LlmResponse complete(String system, String user) throws LlmException {
        var params = ChatCompletionCreateParams.builder()
                .model(cfg.model())
                .maxCompletionTokens(cfg.maxTokens())
                .addSystemMessage(system)
                .addUserMessage(user);
        if (cfg.temperature() != null) params.temperature(cfg.temperature());
        if (cfg.reasoningEffort() != null) params.reasoningEffort(ReasoningEffort.of(cfg.reasoningEffort()));
        if (cfg.jsonMode()) params.responseFormat(ResponseFormatJsonObject.builder().build());

        long start = System.nanoTime();
        ChatCompletion resp;
        try {
            resp = client.chat().completions().create(params.build());
        } catch (OpenAIServiceException e) { // got an HTTP error response
            throw new LlmException("[" + name + "] HTTP " + e.statusCode() + ": " + shorten(e.getMessage()), e,
                    LlmException.isRetryableStatus(e.statusCode()));
        } catch (OpenAIException e) {          // network / timeout / parsing
            throw new LlmException("[" + name + "] " + e.getClass().getSimpleName() + ": " + shorten(e.getMessage()), e, true);
        }
        double latency = (System.nanoTime() - start) / 1e9;

        if (resp.choices().isEmpty()) throw new LlmException("[" + name + "] empty response");
        // Cut off by max_tokens (often: reasoning ate the budget before the answer). Not retryable: same input, same cut.
        if (ChatCompletion.Choice.FinishReason.LENGTH.equals(resp.choices().get(0).finishReason())) {
            throw new LlmException("[" + name + "] reply truncated at max_tokens=" + cfg.maxTokens()
                    + " (raise max_tokens or lower reasoning effort)", null, false);
        }
        // Some open models write their reasoning into the reply (<think>...</think>, <thought>...</thought>).
        // Strip it so callers only see the answer (a "{" inside the reasoning would otherwise confuse JSON parsing).
        String text = REASONING_BLOCK.matcher(resp.choices().get(0).message().content().orElse("")).replaceAll("").strip();
        long in = resp.usage().map(u -> u.promptTokens()).orElse(0L);
        long out = resp.usage().map(u -> u.completionTokens()).orElse(0L);
        return new LlmResponse(text, resp.model(), in, out, latency);
    }

    static String shorten(String msg) {
        return msg == null ? "" : (msg.length() > 300 ? msg.substring(0, 300) + "..." : msg);
    }
}
