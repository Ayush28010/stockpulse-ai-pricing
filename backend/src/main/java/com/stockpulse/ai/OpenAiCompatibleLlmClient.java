package com.stockpulse.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int MAX_ERROR_BODY = 300;

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        log.info("LLM gateway {} model={} configured={}", props.endpoint(), props.model(), props.configured());
    }

    @Override
    public boolean isConfigured() {
        return props.configured();
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        ensureConfigured();
        ObjectNode body = body(systemPrompt, userPrompt);
        if (props.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request(body, props.readTimeout()), HttpResponse.BodyHandlers.ofString());
            checkStatus(response.statusCode(), response.body());
            JsonNode content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new LlmUnavailableException("LLM returned an empty completion");
            }
            log.info("LLM completion in {} ms", (System.nanoTime() - started) / 1_000_000);
            return content.asText();
        } catch (HttpTimeoutException e) {
            throw new LlmUnavailableException("LLM timed out after " + props.readTimeout().toSeconds() + "s", e);
        } catch (IOException e) {
            throw new LlmUnavailableException("LLM I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM call interrupted", e);
        }
    }

    @Override
    public void stream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        ensureConfigured();
        ObjectNode body = body(systemPrompt, userPrompt);
        body.put("stream", true);
        try {
            HttpResponse<Stream<String>> response = http.send(request(body, props.streamTimeout()),
                    HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() >= 300) {
                    checkStatus(response.statusCode(), lines.limit(20).collect(Collectors.joining("\n")));
                }
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = mapper.readTree(data).path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        onDelta.accept(delta.asText());
                    }
                }
            }
        } catch (HttpTimeoutException e) {
            throw new LlmUnavailableException("LLM stream timed out after " + props.streamTimeout().toSeconds() + "s", e);
        } catch (IOException | UncheckedIOException e) {
            throw new LlmUnavailableException("LLM stream I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM stream interrupted", e);
        }
    }

    private void ensureConfigured() {
        if (!props.configured()) {
            throw new LlmUnavailableException("LLM_API_KEY is not configured");
        }
    }

    private ObjectNode body(String systemPrompt, String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return body;
    }

    private HttpRequest request(ObjectNode body, Duration timeout) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(props.endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        props.headers().forEach(builder::header);
        return builder.build();
    }

    private static void checkStatus(int status, String body) {
        if (status < 300) {
            return;
        }
        String snippet = body == null ? "" : body.substring(0, Math.min(MAX_ERROR_BODY, body.length()));
        if (status == 429) {
            throw new LlmUnavailableException("LLM rate limit or quota exceeded (HTTP 429)");
        }
        if (status == 401 || status == 403) {
            throw new LlmUnavailableException("LLM rejected credentials (HTTP " + status + ")");
        }
        throw new LlmUnavailableException("LLM HTTP " + status + ": " + snippet);
    }
}
