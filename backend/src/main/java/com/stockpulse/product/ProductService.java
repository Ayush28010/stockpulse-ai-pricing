package com.stockpulse.product;

import com.stockpulse.advisor.ProductContext;
import com.stockpulse.agent.ChangeSource;
import com.stockpulse.agent.InventoryChangedEvent;
import com.stockpulse.api.dto.CreateProductRequest;
import com.stockpulse.api.dto.OrderResponse;
import com.stockpulse.api.dto.PriceHistoryResponse;
import com.stockpulse.api.dto.ProductResponse;
import com.stockpulse.common.ApiException;
import com.stockpulse.config.TriggerProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.suggestion.PricingSuggestionRepository;
import com.stockpulse.suggestion.ReorderSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductService {

    public static final Duration VELOCITY_WINDOW = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final SaleOrderRepository orders;
    private final PriceHistoryRepository priceHistory;
    private final PricingSuggestionRepository pricing;
    private final ReorderSuggestionRepository reorders;
    private final ProductStatusPolicy statusPolicy;
    private final ApplicationEventPublisher events;
    private final TriggerProperties triggers;

    public ProductService(ProductRepository products, SaleOrderRepository orders, PriceHistoryRepository priceHistory,
                          PricingSuggestionRepository pricing, ReorderSuggestionRepository reorders,
                          ProductStatusPolicy statusPolicy, ApplicationEventPublisher events,
                          TriggerProperties triggers) {
        this.products = products;
        this.orders = orders;
        this.priceHistory = priceHistory;
        this.pricing = pricing;
        this.reorders = reorders;
        this.statusPolicy = statusPolicy;
        this.events = events;
        this.triggers = triggers;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (products.existsBySku(request.sku())) {
            throw ApiException.conflict("DUPLICATE_SKU", "SKU '" + request.sku() + "' already exists");
        }
        Product product = new Product(nextId(), request.sku(), request.name().trim(), request.category(),
                request.currentPrice(), request.stockLevel(), request.reorderThreshold(), 0,
                ProductStatus.derive(request.stockLevel(), false));
        product.defineExtensions(request.costPrice(), request.marginFloorPct(), blankToNull(request.supplierId()));
        products.save(product);
        events.publishEvent(new InventoryChangedEvent(product.getId(), ChangeSource.CREATED));
        return view(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(ProductStatus status, Category category) {
        List<Product> all = products.findAll();
        Map<Category, long[]> totals = new EnumMap<>(Category.class);
        for (Product p : all) {
            long[] t = totals.computeIfAbsent(p.getCategory(), c -> new long[2]);
            t[0] += p.getDemandVelocity();
            t[1]++;
        }
        Map<String, Long> pendingPricing = counts(pricing.countByProductWithStatus(SuggestionStatus.PENDING));
        Map<String, Long> pendingReorder = counts(reorders.countByProductWithStatus(SuggestionStatus.PENDING));
        return products.search(status, category).stream()
                .map(p -> {
                    long[] t = totals.get(p.getCategory());
                    long peers = t[1] - 1;
                    Double average = peers == 0 ? null : (double) (t[0] - p.getDemandVelocity()) / peers;
                    return ProductResponse.from(p, ProductContext.flooredAverage(average), triggers.spikeMultiplier(),
                            pendingPricing.getOrDefault(p.getId(), 0L), pendingReorder.getOrDefault(p.getId(), 0L));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String id) {
        return view(find(id));
    }

    /** Fires the agentic loop after commit; the response does not wait for recommendations. */
    @Transactional
    public ProductResponse updateStock(String id, int stockLevel) {
        Product product = find(id);
        product.adjustStock(stockLevel);
        statusPolicy.refresh(product);
        events.publishEvent(new InventoryChangedEvent(id, ChangeSource.STOCK_UPDATED));
        return view(product);
    }

    /** Simulated sale: decrement stock, recompute 24h velocity, fire the loop after commit. */
    @Transactional
    public OrderResponse recordOrder(String id, int quantity) {
        Product product = find(id);
        product.sell(quantity);
        Instant now = Instant.now();
        SaleOrder order = orders.save(new SaleOrder(id, quantity, product.getCurrentPrice(), now));
        product.updateDemandVelocity((int) orders.countByProductIdAndCreatedAtAfter(id, now.minus(VELOCITY_WINDOW)));
        statusPolicy.refresh(product);
        events.publishEvent(new InventoryChangedEvent(id, ChangeSource.ORDER_PLACED));
        return new OrderResponse(order.getId(), quantity, order.getUnitPrice(), order.getCreatedAt(), view(product));
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryResponse> priceHistory(String id) {
        find(id);
        return priceHistory.findByProductIdOrderByChangedAtAsc(id).stream().map(PriceHistoryResponse::from).toList();
    }

    /** Lets velocity decay as orders age out of the 24h window. Refresh only; never triggers the loop. */
    @Scheduled(fixedDelayString = "${stockpulse.velocity.refresh-interval:PT5M}", initialDelayString = "PT1M")
    @Transactional
    public void refreshVelocities() {
        Instant since = Instant.now().minus(VELOCITY_WINDOW);
        int changed = 0;
        for (Product p : products.findAll()) {
            int velocity = (int) orders.countByProductIdAndCreatedAtAfter(p.getId(), since);
            if (velocity != p.getDemandVelocity()) {
                p.updateDemandVelocity(velocity);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Refreshed demand velocity for {} product(s)", changed);
        }
    }

    /** Must be called inside a transaction. */
    public ProductResponse view(Product p) {
        double average = ProductContext.flooredAverage(products.averageVelocityOfPeers(p.getCategory(), p.getId()));
        long pendingPricing = pricing.findByProduct_IdAndStatus(p.getId(), SuggestionStatus.PENDING).size();
        long pendingReorder = reorders.findByProduct_IdAndStatus(p.getId(), SuggestionStatus.PENDING).size();
        return ProductResponse.from(p, average, triggers.spikeMultiplier(), pendingPricing, pendingReorder);
    }

    private Product find(String id) {
        return products.findById(id).orElseThrow(() -> ApiException.notFound("Product", id));
    }

    private String nextId() {
        int max = products.findAllIds().stream()
                .filter(id -> id.matches("PRD-\\d+"))
                .mapToInt(id -> Integer.parseInt(id.substring(4)))
                .max().orElse(0);
        return String.format("PRD-%03d", max + 1);
    }

    private static Map<String, Long> counts(List<Object[]> rows) {
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
