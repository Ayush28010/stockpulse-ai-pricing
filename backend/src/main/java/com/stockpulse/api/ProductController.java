package com.stockpulse.api;

import com.stockpulse.api.dto.CreateProductRequest;
import com.stockpulse.api.dto.OrderResponse;
import com.stockpulse.api.dto.PlaceOrderRequest;
import com.stockpulse.api.dto.PriceHistoryResponse;
import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ProductResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.api.dto.UpdateStockRequest;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.product.ProductService;
import com.stockpulse.recommendation.RecommendationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final ProductService products;
    private final RecommendationService recommendations;
    private final TaskExecutor streamExecutor;

    public ProductController(ProductService products, RecommendationService recommendations,
                             @Qualifier("streamExecutor") TaskExecutor streamExecutor) {
        this.products = products;
        this.recommendations = recommendations;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = products.create(request);
        return ResponseEntity.created(URI.create("/products/" + created.id())).body(created);
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) ProductStatus status,
                                      @RequestParam(required = false) Category category) {
        return products.list(status, category);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable String id) {
        return products.get(id);
    }

    @PatchMapping("/{id}/stock")
    public ProductResponse updateStock(@PathVariable String id, @Valid @RequestBody UpdateStockRequest request) {
        return products.updateStock(id, request.stockLevel());
    }

    @PostMapping("/{id}/orders")
    public ResponseEntity<OrderResponse> placeOrder(@PathVariable String id,
                                                    @Valid @RequestBody(required = false) PlaceOrderRequest request) {
        int quantity = request == null ? 1 : request.quantityOrDefault();
        return ResponseEntity.status(HttpStatus.CREATED).body(products.recordOrder(id, quantity));
    }

    /** Synchronous: the merchandiser is waiting for this answer (bounded by the LLM read timeout, then rule fallback). */
    @PostMapping("/{id}/suggest-pricing")
    public ResponseEntity<PricingSuggestionResponse> suggestPricing(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recommendations.requestPricing(id));
    }

    @PostMapping("/{id}/suggest-reorder")
    public ResponseEntity<ReorderSuggestionResponse> suggestReorder(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recommendations.requestReorder(id));
    }

    /**
     * SSE: events {@code status}, {@code token} (reasoning text), {@code fallback} (why rules were used),
     * {@code suggestion} (the persisted PENDING suggestion), {@code error}.
     */
    @PostMapping(path = "/{id}/suggest-pricing/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggestPricingStream(@PathVariable String id) {
        products.get(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        streamExecutor.execute(() -> {
            try {
                send(emitter, open, "status", Map.of("message", "Analyzing " + id + "..."));
                PricingSuggestionResponse suggestion = recommendations.streamPricing(id,
                        token -> send(emitter, open, "token", Map.of("text", token)));
                if (suggestion.generatedBy() == GeneratedBy.RULE_FALLBACK) {
                    send(emitter, open, "fallback", Map.of("reason", String.valueOf(suggestion.fallbackReason())));
                }
                send(emitter, open, "suggestion", suggestion);
                emitter.complete();
            } catch (RuntimeException e) {
                log.warn("Pricing stream for {} failed", id, e);
                send(emitter, open, "error", Map.of("message", "Stream failed: " + e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/{id}/price-history")
    public List<PriceHistoryResponse> priceHistory(@PathVariable String id) {
        return products.priceHistory(id);
    }

    /** A disconnected client must not abort generation: the suggestion is still persisted. */
    private static void send(SseEmitter emitter, AtomicBoolean open, String event, Object data) {
        if (!open.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            open.set(false);
        }
    }
}
