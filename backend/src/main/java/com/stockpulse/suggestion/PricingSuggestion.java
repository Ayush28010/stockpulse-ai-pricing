package com.stockpulse.suggestion;

import com.stockpulse.advisor.PricingAdvice;
import com.stockpulse.common.MoneyConverter;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "pricing_suggestions")
public class PricingSuggestion extends Suggestion {

    /** Price the advice was computed against; accept fails with STALE_SUGGESTION if the live price differs. */
    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal currentPrice;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false)
    private BigDecimal recommendedPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingTactic tactic;

    @Column(nullable = false)
    private boolean flagged;

    private String flagNote;

    protected PricingSuggestion() {
    }

    public PricingSuggestion(Product product, BigDecimal priceAtAdvice, TriggerReason trigger,
                             String strategyName, PricingAdvice advice) {
        super(product, trigger, advice.confidence(), advice.reasoning(), advice.generatedBy(),
                strategyName, advice.fallbackReason());
        this.currentPrice = priceAtAdvice;
        this.recommendedPrice = advice.recommendedPrice();
        this.direction = advice.direction();
        this.tactic = advice.tactic();
        this.flagged = advice.flagged();
        this.flagNote = advice.flagNote();
    }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getRecommendedPrice() { return recommendedPrice; }
    public PriceDirection getDirection() { return direction; }
    public PricingTactic getTactic() { return tactic; }
    public boolean isFlagged() { return flagged; }
    public String getFlagNote() { return flagNote; }
}
