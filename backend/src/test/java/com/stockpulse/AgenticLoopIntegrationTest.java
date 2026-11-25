package com.stockpulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end over HTTP with the seeded database. No LLM key is configured, so the AI strategy is
 * exercised on its failure path: every trigger must still produce rule-based suggestions (never a silent drop).
 */
@SpringBootTest(properties = {"llm.api-key=", "stockpulse.strategy.default-name=AI",
        "stockpulse.strategy.refresh-interval=PT0S"})
@AutoConfigureMockMvc
class AgenticLoopIntegrationTest {

    private static final String DB = "target/test-db/stockpulse-" + UUID.randomUUID() + ".db";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("stockpulse.db.path", () -> DB);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seededInitialTriggersHaveRun() {
        await().atMost(Duration.ofSeconds(20)).until(() ->
                json(mvc.perform(get("/pricing-suggestions?productId=PRD-003"))).size() > 0);
    }

    @Test
    void orderDropsStockBelowThresholdThenAcceptUpdatesPrice() throws Exception {
        JsonNode order = json(mvc.perform(post("/products/PRD-003/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}")).andExpect(status().isCreated()));
        assertThat(order.at("/product/stockLevel").asInt()).isEqualTo(7);

        await().atMost(Duration.ofSeconds(20)).until(() ->
                pending("pricing", "PRD-003", "INVENTORY_LOW").size() == 1
                        && pending("reorder", "PRD-003", "INVENTORY_LOW").size() == 1);

        // Idempotency: more orders while a PENDING INVENTORY_LOW pair exists add nothing.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/products/PRD-003/orders")).andExpect(status().isCreated());
        }
        Thread.sleep(1500);
        assertThat(pending("pricing", "PRD-003", "INVENTORY_LOW")).hasSize(1);
        assertThat(pending("reorder", "PRD-003", "INVENTORY_LOW")).hasSize(1);

        JsonNode suggestion = pending("pricing", "PRD-003", "INVENTORY_LOW").get(0);
        assertThat(suggestion.get("generatedBy").asText()).isEqualTo("RULE_FALLBACK");
        assertThat(suggestion.get("fallbackReason").asText()).contains("LLM_API_KEY");
        assertThat(suggestion.get("recommendedPrice").decimalValue()).isEqualByComparingTo("27.49");

        // Human checkpoint: price is unchanged until accept.
        JsonNode before = json(mvc.perform(get("/products/PRD-003")).andExpect(status().isOk()));
        assertThat(before.get("currentPrice").decimalValue()).isEqualByComparingTo("24.99");
        assertThat(before.get("status").asText()).isEqualTo("PRICE_REVIEW_PENDING");

        JsonNode decided = json(mvc.perform(patch("/pricing-suggestions/" + suggestion.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}")).andExpect(status().isOk()));
        assertThat(decided.at("/suggestion/status").asText()).isEqualTo("ACCEPTED");
        assertThat(decided.at("/product/currentPrice").decimalValue()).isEqualByComparingTo("27.49");
        assertThat(decided.at("/product/status").asText()).isEqualTo("ACTIVE");
        assertThat(pending("pricing", "PRD-003", "INITIAL")).as("stale INITIAL suggestion superseded").isEmpty();

        JsonNode history = json(mvc.perform(get("/products/PRD-003/price-history")).andExpect(status().isOk()));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("newPrice").decimalValue()).isEqualByComparingTo("27.49");

        mvc.perform(patch("/pricing-suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // Accepting the reorder simulates an inbound shipment and supersedes the other pending reorder.
        JsonNode reorder = pending("reorder", "PRD-003", "INVENTORY_LOW").get(0);
        int stockBefore = json(mvc.perform(get("/products/PRD-003"))).get("stockLevel").asInt();
        JsonNode received = json(mvc.perform(patch("/reorder-suggestions/" + reorder.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}")).andExpect(status().isOk()));
        assertThat(received.at("/product/stockLevel").asInt())
                .isEqualTo(stockBefore + reorder.get("recommendedQuantity").asInt());
        assertThat(pending("reorder", "PRD-003", null)).isEmpty();
    }

    @Test
    void demandSpikeTriggersSeparateSuggestions() throws Exception {
        JsonNode created = json(mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"SKU-HOME-SPIKE","name":"Viral Mug","category":"HOME","currentPrice":19.99,
                 "stockLevel":100,"reorderThreshold":5,"costPrice":8.00,"marginFloorPct":20}"""))
                .andExpect(status().isCreated()));
        String id = created.get("id").asText();
        assertThat(created.get("marginPct").decimalValue()).isEqualByComparingTo("60.0");

        // HOME peers average 2.0 orders/24h -> spike threshold 6.0; the 7th order crosses it.
        for (int i = 0; i < 7; i++) {
            mvc.perform(post("/products/" + id + "/orders")).andExpect(status().isCreated());
        }
        await().atMost(Duration.ofSeconds(20)).until(() ->
                pending("pricing", id, "DEMAND_SPIKE").size() == 1 && pending("reorder", id, "DEMAND_SPIKE").size() == 1);
        assertThat(pending("pricing", id, "INVENTORY_LOW")).isEmpty();
    }

    @Test
    void strategySwitchesAtRuntimeForOnDemandCalls() throws Exception {
        mvc.perform(put("/settings/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"rule\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value("RULE"));
        mvc.perform(post("/products/PRD-001/suggest-pricing")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedBy").value("RULE"))
                .andExpect(jsonPath("$.triggerReason").value("MANUAL"));

        mvc.perform(put("/settings/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"AI\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value("AI"));
        mvc.perform(post("/products/PRD-001/suggest-reorder")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedBy").value("RULE_FALLBACK"))
                .andExpect(jsonPath("$.strategyName").value("AI"));

        // A second manual request supersedes the first instead of piling up.
        mvc.perform(post("/products/PRD-001/suggest-pricing")).andExpect(status().isCreated());
        assertThat(pending("pricing", "PRD-001", "MANUAL")).hasSize(1);

        mvc.perform(put("/settings/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"MAGIC\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_STRATEGY"));
    }

    @Test
    void strategyEditedDirectlyInConfigTableAppliesWithoutRestart() throws Exception {
        String upsert = "INSERT OR REPLACE INTO app_settings (setting_key, setting_value, updated_at) VALUES ('commerce.strategy', ?, 0)";
        jdbc.update(upsert, "RULE");
        mvc.perform(get("/settings/strategy")).andExpect(jsonPath("$.active").value("RULE"));
        mvc.perform(post("/products/PRD-004/suggest-pricing")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedBy").value("RULE"));
        jdbc.update(upsert, "AI");
        mvc.perform(get("/settings/strategy")).andExpect(jsonPath("$.active").value("AI"));
    }

    @Test
    void streamingEndpointEmitsTokensThenSuggestion() throws Exception {
        MvcResult result = mvc.perform(post("/products/PRD-002/suggest-pricing/stream"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(20_000);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:status", "event:token", "event:fallback", "event:suggestion");
    }

    @Test
    void validationAndConflictsReturnProblemDetails() throws Exception {
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{\"sku\":\"bad sku\",\"currentPrice\":-1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sku":"SKU-ELEC-001","name":"Dup","category":"ELECTRONICS","currentPrice":1,"stockLevel":1,"reorderThreshold":1}"""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        mvc.perform(get("/products?category=TOYS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/products?category=APPAREL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/products/PRD-006/orders"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(post("/products/PRD-002/orders").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/products/PRD-002/stock").contentType(MediaType.APPLICATION_JSON).content("{\"stockLevel\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/pricing-suggestions/1").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DECISION"));
        mvc.perform(get("/products/NOPE")).andExpect(status().isNotFound());
        mvc.perform(post("/products/PRD-002/orders").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnsupportedMediaType());
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private java.util.List<JsonNode> pending(String type, String productId, String trigger) throws Exception {
        JsonNode list = json(mvc.perform(get("/" + type + "-suggestions?status=PENDING&productId=" + productId)));
        java.util.List<JsonNode> out = new java.util.ArrayList<>();
        list.forEach(n -> {
            if (trigger == null || trigger.equals(n.get("triggerReason").asText())) {
                out.add(n);
            }
        });
        return out;
    }
}
