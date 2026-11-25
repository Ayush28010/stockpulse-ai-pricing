package com.stockpulse.product;

import com.stockpulse.common.InstantMillisConverter;
import com.stockpulse.common.MoneyConverter;
import com.stockpulse.domain.TriggerReason;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_history")
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String productId;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal oldPrice;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal newPrice;

    private Long pricingSuggestionId;

    @Enumerated(EnumType.STRING)
    private TriggerReason triggerReason;

    @Convert(converter = InstantMillisConverter.class)
    @Column(nullable = false)
    private Instant changedAt;

    protected PriceHistory() {
    }

    public PriceHistory(String productId, BigDecimal oldPrice, BigDecimal newPrice,
                        Long pricingSuggestionId, TriggerReason triggerReason) {
        this.productId = productId;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.pricingSuggestionId = pricingSuggestionId;
        this.triggerReason = triggerReason;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProductId() { return productId; }
    public BigDecimal getOldPrice() { return oldPrice; }
    public BigDecimal getNewPrice() { return newPrice; }
    public Long getPricingSuggestionId() { return pricingSuggestionId; }
    public TriggerReason getTriggerReason() { return triggerReason; }
    public Instant getChangedAt() { return changedAt; }
}
