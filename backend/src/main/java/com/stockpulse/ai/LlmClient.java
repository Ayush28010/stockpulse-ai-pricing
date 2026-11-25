package com.stockpulse.ai;

import java.util.function.Consumer;

/** Raw text in, raw text out. Prompting, parsing, validation and fallback live in {@link AiCommerceStrategy}. */
public interface LlmClient {

    boolean isConfigured();

    String model();

    /** @throws LlmUnavailableException on missing key, timeout, quota, HTTP or envelope errors */
    String complete(String systemPrompt, String userPrompt);

    /** Streams content deltas as they arrive. @throws LlmUnavailableException as {@link #complete}. */
    void stream(String systemPrompt, String userPrompt, Consumer<String> onDelta);
}
