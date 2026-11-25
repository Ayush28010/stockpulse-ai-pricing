package com.stockpulse.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.advisor.Advice;
import com.stockpulse.advisor.PricingAdvice;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.advisor.ReorderAdvice;
import com.stockpulse.advisor.RuleBasedStrategy;
import com.stockpulse.advisor.StreamingCommerceStrategy;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Unified AI call: one prompt per trigger returns pricing and reorder together; each half is validated
 * independently and replaced by the rule-based result if invalid, so one bad half never discards the other.
 */
@Component
public class AiCommerceStrategy implements StreamingCommerceStrategy {

    public static final String NAME = "AI";
    static final String JSON_MARKER = "===JSON===";
    private static final Logger log = LoggerFactory.getLogger(AiCommerceStrategy.class);

    private final LlmClient llm;
    private final PromptFactory prompts;
    private final AiAdviceParser parser;
    private final AiAdviceValidator validator;
    private final RuleBasedStrategy rules;

    public AiCommerceStrategy(LlmClient llm, PromptFactory prompts, AiAdviceParser parser,
                              AiAdviceValidator validator, RuleBasedStrategy rules) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.validator = validator;
        this.rules = rules;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "LLM merchandising advisor (" + llm.model() + "): trigger-specific prompts, validated output, "
                + "per-half rule fallback.";
    }

    @Override
    public Advice advise(ProductContext ctx, TriggerReason trigger, Set<SuggestionType> wanted) {
        Prompt prompt = prompts.build(ctx, trigger, baseline(ctx, trigger), false);
        JsonNode root;
        try {
            String raw = llm.complete(prompt.system(), prompt.user());
            log.debug("LLM raw response for {} ({}): {}", ctx.productId(), prompt.template(), raw);
            root = parser.parse(raw);
        } catch (LlmUnavailableException e) {
            log.warn("AI advice for {} ({}) falling back to rules: {}", ctx.productId(), trigger, e.getMessage());
            return rules.fallback(ctx, wanted, "LLM unavailable: " + e.getMessage());
        } catch (InvalidAiOutputException e) {
            log.warn("AI output for {} ({}) unparseable: {}", ctx.productId(), trigger, e.getMessage());
            return rules.fallback(ctx, wanted, "Unparseable LLM output: " + e.getMessage());
        }
        PricingAdvice pricing = wanted.contains(SuggestionType.PRICING) ? pricingOrFallback(ctx, root.get("pricing")) : null;
        ReorderAdvice reorder = wanted.contains(SuggestionType.REORDER) ? reorderOrFallback(ctx, root.get("reorder")) : null;
        log.info("AI advice for {} via {} prompt: pricing={} reorder={}", ctx.productId(), prompt.template(),
                pricing == null ? "-" : pricing.generatedBy(), reorder == null ? "-" : reorder.generatedBy());
        return new Advice(pricing, reorder);
    }

    @Override
    public PricingAdvice streamPricing(ProductContext ctx, TriggerReason trigger, Consumer<String> onReasoningToken) {
        Prompt prompt = prompts.build(ctx, trigger, baseline(ctx, trigger), true);
        ReasoningStreamSplitter splitter = new ReasoningStreamSplitter(JSON_MARKER, onReasoningToken);
        try {
            llm.stream(prompt.system(), prompt.user(), splitter::accept);
        } catch (LlmUnavailableException e) {
            splitter.finish();
            return streamedFallback(ctx, "LLM unavailable: " + e.getMessage(), onReasoningToken);
        }
        if (!splitter.markerSeen()) {
            splitter.finish();
        }
        try {
            return validator.validatePricing(ctx, parser.parse(splitter.jsonPart()).get("pricing"));
        } catch (InvalidAiOutputException e) {
            return streamedFallback(ctx, "AI pricing rejected: " + e.getMessage(), onReasoningToken);
        }
    }

    private PricingAdvice streamedFallback(ProductContext ctx, String reason, Consumer<String> onReasoningToken) {
        PricingAdvice fallback = rules.pricing(ctx).asFallback(reason);
        onReasoningToken.accept("\n[Fallback to rules: " + reason + "]\n" + fallback.reasoning());
        return fallback;
    }

    private PricingAdvice pricingOrFallback(ProductContext ctx, JsonNode node) {
        try {
            return validator.validatePricing(ctx, node);
        } catch (InvalidAiOutputException e) {
            log.warn("AI pricing for {} rejected: {}", ctx.productId(), e.getMessage());
            return rules.pricing(ctx).asFallback("AI pricing rejected: " + e.getMessage());
        }
    }

    private ReorderAdvice reorderOrFallback(ProductContext ctx, JsonNode node) {
        try {
            return validator.validateReorder(ctx, node);
        } catch (InvalidAiOutputException e) {
            log.warn("AI reorder for {} rejected: {}", ctx.productId(), e.getMessage());
            return rules.reorder(ctx).asFallback("AI reorder rejected: " + e.getMessage());
        }
    }

    private Advice baseline(ProductContext ctx, TriggerReason trigger) {
        return rules.advise(ctx, trigger, EnumSet.allOf(SuggestionType.class));
    }
}
