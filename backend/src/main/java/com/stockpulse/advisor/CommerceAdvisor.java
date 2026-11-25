package com.stockpulse.advisor;

import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single entry point used by both the HTTP on-demand endpoints and the async agentic loop.
 * Resolves the active strategy, guarantees every wanted half is produced (rule fallback as a safety net),
 * and runs the guardrail chain over any strategy's pricing output.
 */
@Component
public class CommerceAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CommerceAdvisor.class);

    private final StrategyRegistry registry;
    private final RuleBasedStrategy rules;
    private final List<PriceGuardrail> guardrails;

    public CommerceAdvisor(StrategyRegistry registry, RuleBasedStrategy rules, List<PriceGuardrail> guardrails) {
        this.registry = registry;
        this.rules = rules;
        this.guardrails = guardrails;
    }

    public AdvisorResult advise(ProductContext ctx, TriggerReason trigger, Set<SuggestionType> wanted) {
        CommerceStrategy strategy = registry.active();
        Advice advice;
        String failure = null;
        try {
            advice = strategy.advise(ctx, trigger, wanted);
        } catch (RuntimeException e) {
            log.warn("Strategy {} failed for {} ({}): {}", strategy.name(), ctx.productId(), trigger, e.toString());
            advice = new Advice(null, null);
            failure = "Strategy " + strategy.name() + " failed: " + e.getMessage();
        }
        String reason = failure != null ? failure : "Strategy " + strategy.name() + " returned no advice";

        PricingAdvice pricing = null;
        if (wanted.contains(SuggestionType.PRICING)) {
            pricing = advice.pricing() != null ? advice.pricing() : rules.pricing(ctx).asFallback(reason);
            pricing = guard(ctx, pricing);
        }
        ReorderAdvice reorder = null;
        if (wanted.contains(SuggestionType.REORDER)) {
            reorder = advice.reorder() != null ? advice.reorder() : rules.reorder(ctx).asFallback(reason);
        }
        return new AdvisorResult(strategy.name(), pricing, reorder);
    }

    /** Streams reasoning when the active strategy supports it; otherwise emits the final reasoning as one token. */
    public AdvisorResult streamPricing(ProductContext ctx, TriggerReason trigger, Consumer<String> onToken) {
        CommerceStrategy strategy = registry.active();
        PricingAdvice pricing = null;
        String reason = "Strategy " + strategy.name() + " returned no advice";
        try {
            if (strategy instanceof StreamingCommerceStrategy streaming) {
                pricing = streaming.streamPricing(ctx, trigger, onToken);
            } else {
                pricing = strategy.advise(ctx, trigger, EnumSet.of(SuggestionType.PRICING)).pricing();
                if (pricing != null) {
                    onToken.accept(pricing.reasoning());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Streaming strategy {} failed for {}: {}", strategy.name(), ctx.productId(), e.toString());
            reason = "Strategy " + strategy.name() + " failed: " + e.getMessage();
        }
        if (pricing == null) {
            pricing = rules.pricing(ctx).asFallback(reason);
            onToken.accept(pricing.reasoning());
        }
        return new AdvisorResult(strategy.name(), guard(ctx, pricing), null);
    }

    private PricingAdvice guard(ProductContext ctx, PricingAdvice advice) {
        PricingAdvice guarded = advice;
        for (PriceGuardrail guardrail : guardrails) {
            guarded = guardrail.apply(ctx, guarded);
        }
        return guarded;
    }
}
