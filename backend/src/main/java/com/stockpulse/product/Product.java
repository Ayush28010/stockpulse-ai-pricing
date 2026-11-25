package com.stockpulse.product;

import com.stockpulse.common.ApiException;
import com.stockpulse.common.InstantMillisConverter;
import com.stockpulse.common.Money;
import com.stockpulse.common.MoneyConverter;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal currentPrice;

    @Column(nullable = false)
    private int stockLevel;

    @Column(nullable = false)
    private int reorderThreshold;

    /** Orders in the trailing 24h; cached from sale_orders on every order and by a periodic refresh. */
    @Column(nullable = false)
    private int demandVelocity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    // Sprint 2 extension points, nullable today. costPrice/marginFloorPct already feed MarginFloorGuardrail.
    @Convert(converter = MoneyConverter.class)
    private BigDecimal costPrice;

    private BigDecimal marginFloorPct;

    private String supplierId;

    @Version
    private Long version;

    @Convert(converter = InstantMillisConverter.class)
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Convert(converter = InstantMillisConverter.class)
    @Column(nullable = false)
    private Instant updatedAt;

    protected Product() {
    }

    public Product(String id, String sku, String name, Category category, BigDecimal currentPrice,
                   int stockLevel, int reorderThreshold, int demandVelocity, ProductStatus status) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.currentPrice = Money.round(currentPrice);
        this.stockLevel = stockLevel;
        this.reorderThreshold = reorderThreshold;
        this.demandVelocity = demandVelocity;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void sell(int quantity) {
        if (quantity <= 0) {
            throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be positive");
        }
        if (quantity > stockLevel) {
            throw ApiException.conflict("INSUFFICIENT_STOCK",
                    "Product '" + id + "' has " + stockLevel + " units; cannot sell " + quantity);
        }
        stockLevel -= quantity;
    }

    public void adjustStock(int newLevel) {
        if (newLevel < 0) {
            throw ApiException.badRequest("INVALID_STOCK", "Stock level cannot be negative");
        }
        stockLevel = newLevel;
    }

    public void receiveStock(int quantity) {
        stockLevel = Math.addExact(stockLevel, quantity);
    }

    public void changePrice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.signum() <= 0) {
            throw ApiException.badRequest("INVALID_PRICE", "Price must be positive");
        }
        currentPrice = Money.round(newPrice);
    }

    public void updateDemandVelocity(int velocity) {
        demandVelocity = Math.max(0, velocity);
    }

    public void defineExtensions(BigDecimal costPrice, BigDecimal marginFloorPct, String supplierId) {
        this.costPrice = Money.round(costPrice);
        this.marginFloorPct = marginFloorPct;
        this.supplierId = supplierId;
    }

    public void transitionTo(ProductStatus next) {
        if (next == status) {
            return;
        }
        if (!status.canTransitionTo(next)) {
            throw ApiException.invalidTransition("Product", id, status, next);
        }
        status = next;
    }

    public boolean isBelowReorderThreshold() {
        return stockLevel < reorderThreshold;
    }

    public String getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public Category getCategory() { return category; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public int getStockLevel() { return stockLevel; }
    public int getReorderThreshold() { return reorderThreshold; }
    public int getDemandVelocity() { return demandVelocity; }
    public ProductStatus getStatus() { return status; }
    public BigDecimal getCostPrice() { return costPrice; }
    public BigDecimal getMarginFloorPct() { return marginFloorPct; }
    public String getSupplierId() { return supplierId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
