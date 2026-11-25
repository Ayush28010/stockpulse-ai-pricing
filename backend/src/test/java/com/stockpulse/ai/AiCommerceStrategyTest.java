package com.stockpulse.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.advisor.Advice;
import com.stockpulse.advisor.PricingAdvice;
import com.stockpulse.advisor.RuleBasedStrategy;
import com.stockpulse.config.GuardrailProperties;
import com.stockpulse.config.RuleProperties;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.stockpulse.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

/** "Test broken LLM": every failure mode must still yield both suggestions. */
class AiCommerceStrategyTest {

    private static final String VALID = """
            {"pricing":{"tactic":"PROTECT_INVENTORY","recommendedPrice":27.99,"confidence":0.82,"reasoning":"scarce"},
             "reorder":{"recommendedQuantity":150,"leadTimeDays":10,"confidence":0.78,"reasoning":"cover lead time"}}""";

    private static AiCommerceStrategy strategy(Supplier<String> response) {
        LlmClient llm = new LlmClient() {
            public boolean isConfigured() { return true; }
            public String model() { return "fake"; }
            public String complete(String s, String u) { return response.get(); }
            public void stream(String s, String u, Consumer<String> onDelta) {
                String text = response.get();
                for (int i = 0; i < text.length(); i += 7) {
                    onDelta.accept(text.substring(i, Math.min(text.length(), i + 7)));
                }
            }
        };
        RuleBasedStrategy rules = new RuleBasedStrategy(new RuleProperties(2.0, 10, 5, 3));
        return new AiCommerceStrategy(llm, new PromptFactory(), new AiAdviceParser(new ObjectMapper()),
                new AiAdviceValidator(new GuardrailProperties(0.25, 4.0, 30, 20, 60)), rules);
    }

    private static Advice run(Supplier<String> response) {
        return strategy(response).advise(ctx("24.99", 8, 15, 12), TriggerReason.INVENTORY_LOW,
                EnumSet.allOf(SuggestionType.class));
    }

    @Test
    void validResponseProducesAiAdviceForBothHalves() {
        Advice a = run(() -> VALID);
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.AI);
        assertThat(a.pricing().recommendedPrice()).isEqualByComparingTo("27.99");
        assertThat(a.reorder().recommendedQuantity()).isEqualTo(150);
    }

    @Test
    void codeFencedJsonIsAccepted() {
        assertThat(run(() -> "```json\n" + VALID + "\n```").pricing().generatedBy()).isEqualTo(GeneratedBy.AI);
    }

    @Test
    void timeoutFallsBackToRulesForBoth() {
        Advice a = run(() -> { throw new LlmUnavailableException("LLM timed out after 25s"); });
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
        assertThat(a.reorder().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
        assertThat(a.pricing().fallbackReason()).contains("timed out");
        assertThat(a.pricing().recommendedPrice()).isEqualByComparingTo("27.49");
    }

    @Test
    void quotaErrorFallsBack() {
        Advice a = run(() -> { throw new LlmUnavailableException("LLM rate limit or quota exceeded (HTTP 429)"); });
        assertThat(a.reorder().fallbackReason()).contains("429");
    }

    @Test
    void malformedJsonFallsBack() {
        Advice a = run(() -> "Sure! Here is my answer: {pricing: oops");
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
        assertThat(a.pricing().fallbackReason()).startsWith("Unparseable");
    }

    @Test
    void absurdPriceFallsBackOnlyForPricingHalf() {
        Advice a = run(() -> VALID.replace("27.99", "999999"));
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
        assertThat(a.pricing().fallbackReason()).contains("allowed range");
        assertThat(a.reorder().generatedBy()).isEqualTo(GeneratedBy.AI);
    }

    @Test
    void zeroPriceFallsBack() {
        assertThat(run(() -> VALID.replace("27.99", "0")).pricing().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
    }

    @Test
    void badQuantityFallsBackOnlyForReorderHalf() {
        Advice a = run(() -> VALID.replace("150", "-3"));
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.AI);
        assertThat(a.reorder().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
    }

    @Test
    void streamingEmitsProseBeforeMarkerAndParsesJsonAfter() {
        List<String> tokens = new ArrayList<>();
        PricingAdvice p = strategy(() -> "Stock is scarce and demand strong.\n===JSON===\n" + VALID)
                .streamPricing(ctx("24.99", 8, 15, 12), TriggerReason.MANUAL, tokens::add);
        String streamed = String.join("", tokens);
        assertThat(streamed).contains("Stock is scarce").doesNotContain("===JSON").doesNotContain("recommendedPrice");
        assertThat(p.generatedBy()).isEqualTo(GeneratedBy.AI);
        assertThat(p.recommendedPrice()).isEqualByComparingTo("27.99");
    }

    @Test
    void streamingFailureFallsBack() {
        PricingAdvice p = strategy(() -> { throw new LlmUnavailableException("down"); })
                .streamPricing(ctx("24.99", 8, 15, 12), TriggerReason.MANUAL, t -> { });
        assertThat(p.generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
    }
}
