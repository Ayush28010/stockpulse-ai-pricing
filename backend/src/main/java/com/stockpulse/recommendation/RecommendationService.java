package com.stockpulse.recommendation;

import com.stockpulse.advisor.AdvisorResult;
import com.stockpulse.advisor.CommerceAdvisor;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.ProductContextLoader;
import com.stockpulse.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The one generation path shared by HTTP (on-demand) and async (agentic) callers:
 * load context -> CommerceAdvisor (active strategy + fallback + guardrails) -> persist.
 * No transaction is open while the strategy (possibly an LLM) runs.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final ProductContextLoader contexts;
    private final CommerceAdvisor advisor;
    private final SuggestionService suggestions;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public RecommendationService(ProductContextLoader contexts, CommerceAdvisor advisor, SuggestionService suggestions) {
        this.contexts = contexts;
        this.advisor = advisor;
        this.suggestions = suggestions;
    }

    /** Agentic path. Idempotent per product + trigger + type. */
    public void generateForTrigger(String productId, TriggerReason trigger) {
        String key = productId + ":" + trigger;
        if (!inFlight.add(key)) {
            log.info("{} {}: generation already in flight, skipping duplicate trigger", productId, trigger);
            return;
        }
        try {
            Set<SuggestionType> missing = suggestions.typesWithoutPending(productId, trigger);
            if (missing.isEmpty()) {
                log.info("{} {}: PENDING pricing and reorder already exist, skipping", productId, trigger);
                return;
            }
            ProductContext ctx = contexts.load(productId);
            AdvisorResult result = advisor.advise(ctx, trigger, missing);
            suggestions.save(ctx, trigger, result);
        } catch (DataIntegrityViolationException e) {
            log.info("{} {}: concurrent duplicate blocked by unique PENDING index", productId, trigger);
        } finally {
            inFlight.remove(key);
        }
    }

    public PricingSuggestionResponse requestPricing(String productId) {
        ProductContext ctx = contexts.load(productId);
        AdvisorResult result = advisor.advise(ctx, TriggerReason.MANUAL, EnumSet.of(SuggestionType.PRICING));
        return suggestions.save(ctx, TriggerReason.MANUAL, result).pricing();
    }

    public ReorderSuggestionResponse requestReorder(String productId) {
        ProductContext ctx = contexts.load(productId);
        AdvisorResult result = advisor.advise(ctx, TriggerReason.MANUAL, EnumSet.of(SuggestionType.REORDER));
        return suggestions.save(ctx, TriggerReason.MANUAL, result).reorder();
    }

    public PricingSuggestionResponse streamPricing(String productId, Consumer<String> onToken) {
        ProductContext ctx = contexts.load(productId);
        AdvisorResult result = advisor.streamPricing(ctx, TriggerReason.MANUAL, onToken);
        return suggestions.save(ctx, TriggerReason.MANUAL, result).pricing();
    }
}
