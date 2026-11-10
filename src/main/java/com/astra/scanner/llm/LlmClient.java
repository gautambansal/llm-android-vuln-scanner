package com.astra.scanner.llm;

/** The one interface every model sits behind. The rest of the code only knows this. */
public interface LlmClient {

    /** The config entry name, e.g. "groq-llama-8b". */
    String name();

    /** Send one system + user prompt; return the text reply plus usage stats. */
    LlmResponse complete(String system, String user) throws LlmException;
}
