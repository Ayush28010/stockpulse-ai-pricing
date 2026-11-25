package com.stockpulse.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/** OpenAI-compatible gateway settings (LiteLLM, Groq, Ollama all speak this protocol). */
@ConfigurationProperties("llm")
public record LlmProperties(
        @DefaultValue("https://litellm-qc.zycus.net") String baseUrl,
        @DefaultValue("/v1/chat/completions") String chatPath,
        String apiKey,
        @DefaultValue("gpt-5.6-luna-azure-payg-global") String model,
        Map<String, String> extraHeaders,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("25s") Duration readTimeout,
        @DefaultValue("60s") Duration streamTimeout,
        @DefaultValue("true") boolean jsonMode) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, String> headers() {
        return extraHeaders == null ? Map.of() : extraHeaders;
    }

    public String endpoint() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + chatPath;
    }
}
