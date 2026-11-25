package com.stockpulse.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Extracts the JSON object from a completion, tolerating code fences or stray prose around it. */
@Component
public class AiAdviceParser {

    private final ObjectMapper mapper;

    public AiAdviceParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAiOutputException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidAiOutputException("no JSON object in response");
        }
        try {
            JsonNode node = mapper.readTree(raw.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw new InvalidAiOutputException("response is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidAiOutputException("malformed JSON: " + e.getOriginalMessage());
        }
    }
}
