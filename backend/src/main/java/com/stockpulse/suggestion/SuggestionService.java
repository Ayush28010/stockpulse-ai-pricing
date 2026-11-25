package com.stockpulse.suggestion;

import com.stockpulse.advisor.AdvisorResult;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.agent.ChangeSource;
import com.stockpulse.agent.InventoryChangedEvent;
import com.stockpulse.api.dto.DecisionResponse;
import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.common.ApiException;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.PriceHistory;
import com.stockpulse.product.PriceHistoryRepository;
import com.stockpulse.product.Product;
import com.stockpulse.product.ProductRepository;
import com.stockpulse.product.ProductService;
import com.stockpulse.product.ProductStatusPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Persistence and the human checkpoint. Nothing here calls an LLM, so transactions stay short. */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    private static final int MAX_LIST = 500;

    private final ProductRepository products;
    private final PricingSuggestionRepository pricing;
    private final ReorderSuggestionRepository reorders;
    private final PriceHistoryRepository priceHistory;
    private final ProductStatusPolicy statusPolicy;
    private final ProductService productService;
    private final ApplicationEventPublisher events;

    public SuggestionService(ProductRepository products, PricingSuggestionRepository pricing,
                             ReorderSuggestionRepository reorders, PriceHistoryRepository priceHistory,
                             ProductStatusPolicy statusPolicy, ProductService productService,
                             ApplicationEventPublisher events) {
        this.products = products;
        this.pricing = pricing;
        this.reorders = reorders;
        this.priceHistory = priceHistory;
        this.statusPolicy = statusPolicy;
        this.productService = productService;
        this.events = events;
    }

    /** Idempotency pre-check: which suggestion types have no PENDING row for this product + trigger. */
    @Transactional(readOnly = true)
    public Set<SuggestionType> typesWithoutPending(String productId, TriggerReason trigger) {
        Set<SuggestionType> missing = EnumSet.noneOf(SuggestionType.class);
        if (!pricing.existsByProduct_IdAndTriggerReasonAndStatus(productId, trigger, SuggestionStatus.PENDING)) {
            missing.add(SuggestionType.PRICING);
        }
        if (!reorders.existsByProduct_IdAndTriggerReasonAndStatus(productId, trigger, SuggestionStatus.PENDING)) {
            missing.add(SuggestionType.REORDER);
        }
        return missing;
    }

    public record Saved(PricingSuggestionResponse pricing, ReorderSuggestionResponse reorder) {
    }

    /**
     * Agentic triggers skip a type that already has a PENDING row (re-checked here, enforced by a partial unique index).
     * MANUAL requests supersede the previous PENDING manual suggestion, since the merchandiser asked for a fresh one.
     */
    @Transactional
    public Saved save(ProductContext ctx, TriggerReason trigger, AdvisorResult result) {
        Product product = products.findById(ctx.productId())
                .orElseThrow(() -> ApiException.notFound("Product", ctx.productId()));
        boolean manual = trigger == TriggerReason.MANUAL;

        PricingSuggestion savedPricing = null;
        if (result.pricing() != null) {
            List<PricingSuggestion> pending = pricing.findByProduct_IdAndTriggerReasonAndStatus(
                    product.getId(), trigger, SuggestionStatus.PENDING);
            if (manual) {
                pending.forEach(s -> s.reject("Superseded by a newer manual request"));
                pricing.flush();
            }
            if (manual || pending.isEmpty()) {
                savedPricing = pricing.save(new PricingSuggestion(product, ctx.currentPrice(), trigger,
                        result.strategyName(), result.pricing()));
            }
        }

        ReorderSuggestion savedReorder = null;
        if (result.reorder() != null) {
            List<ReorderSuggestion> pending = reorders.findByProduct_IdAndTriggerReasonAndStatus(
                    product.getId(), trigger, SuggestionStatus.PENDING);
            if (manual) {
                pending.forEach(s -> s.reject("Superseded by a newer manual request"));
                reorders.flush();
            }
            if (manual || pending.isEmpty()) {
                savedReorder = reorders.save(new ReorderSuggestion(product, ctx.stockLevel(), trigger,
                        result.strategyName(), result.reorder()));
            }
        }

        pricing.flush();
        reorders.flush();
        statusPolicy.refresh(product);
        log.info("{} {}: saved pricing={} reorder={} (strategy {})", product.getId(), trigger,
                savedPricing == null ? "skipped" : savedPricing.getId() + "/" + savedPricing.getGeneratedBy(),
                savedReorder == null ? "skipped" : savedReorder.getId() + "/" + savedReorder.getGeneratedBy(),
                result.strategyName());
        return new Saved(
                savedPricing == null ? null : PricingSuggestionResponse.from(savedPricing),
                savedReorder == null ? null : ReorderSuggestionResponse.from(savedReorder));
    }

    /**
     * Human checkpoint for price. Accept atomically updates the live price, writes price history and
     * supersedes other PENDING pricing suggestions (they were computed against the old price).
     */
    @Transactional
    public DecisionResponse<PricingSuggestionResponse> decidePricing(Long id, SuggestionStatus decision, String note) {
        requireDecision(decision);
        PricingSuggestion suggestion = pricing.findById(id)
                .orElseThrow(() -> ApiException.notFound("Pricing suggestion", id));
        suggestion.assertPending();
        Product product = suggestion.getProduct();

        if (decision == SuggestionStatus.ACCEPTED) {
            BigDecimal live = product.getCurrentPrice();
            if (live.compareTo(suggestion.getCurrentPrice()) != 0) {
                throw ApiException.conflict("STALE_SUGGESTION", "Suggestion was computed for price "
                        + suggestion.getCurrentPrice() + " but the live price is now " + live + "; reject it or request a new one");
            }
            suggestion.accept(note);
            if (live.compareTo(suggestion.getRecommendedPrice()) != 0) {
                product.changePrice(suggestion.getRecommendedPrice());
                priceHistory.save(new PriceHistory(product.getId(), live, suggestion.getRecommendedPrice(),
                        suggestion.getId(), suggestion.getTriggerReason()));
                String supersede = "Superseded: price changed to " + suggestion.getRecommendedPrice()
                        + " by accepted suggestion #" + suggestion.getId();
                pricing.findByProduct_IdAndStatus(product.getId(), SuggestionStatus.PENDING).stream()
                        .filter(other -> !other.getId().equals(suggestion.getId()))
                        .forEach(other -> other.reject(supersede));
            }
        } else {
            suggestion.reject(note);
        }
        pricing.flush();
        statusPolicy.refresh(product);
        return new DecisionResponse<>(PricingSuggestionResponse.from(suggestion), productService.view(product));
    }

    /**
     * Human checkpoint for replenishment. Accept simulates the inbound shipment (stock += quantity) and supersedes
     * other PENDING reorders so the same shortfall is not ordered twice.
     */
    @Transactional
    public DecisionResponse<ReorderSuggestionResponse> decideReorder(Long id, SuggestionStatus decision, String note) {
        requireDecision(decision);
        ReorderSuggestion suggestion = reorders.findById(id)
                .orElseThrow(() -> ApiException.notFound("Reorder suggestion", id));
        suggestion.assertPending();
        Product product = suggestion.getProduct();

        if (decision == SuggestionStatus.ACCEPTED) {
            suggestion.accept(note);
            product.receiveStock(suggestion.getRecommendedQuantity());
            String supersede = "Superseded: inbound shipment of " + suggestion.getRecommendedQuantity()
                    + " units accepted via suggestion #" + suggestion.getId();
            reorders.findByProduct_IdAndStatus(product.getId(), SuggestionStatus.PENDING).stream()
                    .filter(other -> !other.getId().equals(suggestion.getId()))
                    .forEach(other -> other.reject(supersede));
            events.publishEvent(new InventoryChangedEvent(product.getId(), ChangeSource.REORDER_RECEIVED));
        } else {
            suggestion.reject(note);
        }
        reorders.flush();
        statusPolicy.refresh(product);
        return new DecisionResponse<>(ReorderSuggestionResponse.from(suggestion), productService.view(product));
    }

    @Transactional(readOnly = true)
    public List<PricingSuggestionResponse> listPricing(SuggestionStatus status, String productId, int limit) {
        return pricing.search(status, productId, PageRequest.of(0, clamp(limit))).stream()
                .map(PricingSuggestionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestionResponse> listReorder(SuggestionStatus status, String productId, int limit) {
        return reorders.search(status, productId, PageRequest.of(0, clamp(limit))).stream()
                .map(ReorderSuggestionResponse::from).toList();
    }

    private static void requireDecision(SuggestionStatus decision) {
        if (decision == null || decision == SuggestionStatus.PENDING) {
            throw ApiException.badRequest("INVALID_DECISION", "status must be ACCEPTED or REJECTED");
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(MAX_LIST, limit));
    }
}
