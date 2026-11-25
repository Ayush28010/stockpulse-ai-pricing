package com.stockpulse.product;

import com.stockpulse.agent.ChangeSource;
import com.stockpulse.agent.InventoryChangedEvent;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Addendum A seed data, loaded once into an empty database. Synthetic orders are spread over the last 24h
 * so computed velocity equals the seeded value. Each product then gets an INITIAL trigger evaluation.
 */
@Component
@ConditionalOnProperty(name = "stockpulse.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final ProductRepository products;
    private final SaleOrderRepository orders;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public SeedDataLoader(ProductRepository products, SaleOrderRepository orders, TransactionTemplate tx,
                          ApplicationEventPublisher events) {
        this.products = products;
        this.orders = orders;
        this.tx = tx;
        this.events = events;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (products.count() > 0) {
            return;
        }
        List<Product> seed = List.of(
                product("PRD-001", "SKU-ELEC-001", "Wireless Earbuds Pro", Category.ELECTRONICS, "79.99", 45, 20, 3, ProductStatus.ACTIVE),
                product("PRD-002", "SKU-ELEC-002", "USB-C Hub 7-Port", Category.ELECTRONICS, "34.99", 120, 30, 1, ProductStatus.ACTIVE),
                product("PRD-003", "SKU-APP-001", "Organic Cotton T-Shirt", Category.APPAREL, "24.99", 8, 15, 12, ProductStatus.PRICE_REVIEW_PENDING),
                product("PRD-004", "SKU-APP-002", "Running Shorts — Navy", Category.APPAREL, "39.99", 55, 20, 2, ProductStatus.ACTIVE),
                product("PRD-005", "SKU-HOME-001", "Ceramic Pour-Over Set", Category.HOME, "49.99", 22, 10, 4, ProductStatus.ACTIVE),
                product("PRD-006", "SKU-HOME-002", "LED Desk Lamp — Dimmable", Category.HOME, "59.99", 0, 15, 0, ProductStatus.OUT_OF_STOCK),
                product("PRD-007", "SKU-ELEC-003", "Portable Charger 20K", Category.ELECTRONICS, "44.99", 18, 25, 8, ProductStatus.ACTIVE),
                product("PRD-008", "SKU-APP-003", "Hoodie — Heather Grey", Category.APPAREL, "54.99", 11, 12, 15, ProductStatus.ACTIVE));

        tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            Duration window = Duration.ofHours(23);
            for (Product p : seed) {
                products.save(p);
                int velocity = p.getDemandVelocity();
                for (int i = 0; i < velocity; i++) {
                    Instant at = now.minus(window.multipliedBy(i + 1).dividedBy(velocity + 1));
                    orders.save(new SaleOrder(p.getId(), 1, p.getCurrentPrice(), at));
                }
            }
        });
        log.info("Seeded {} products (Addendum A)", seed.size());
        seed.forEach(p -> events.publishEvent(new InventoryChangedEvent(p.getId(), ChangeSource.SEEDED)));
    }

    private static Product product(String id, String sku, String name, Category category, String price,
                                   int stock, int threshold, int velocity, ProductStatus status) {
        return new Product(id, sku, name, category, new BigDecimal(price), stock, threshold, velocity, status);
    }
}
