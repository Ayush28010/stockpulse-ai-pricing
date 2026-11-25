package com.stockpulse.suggestion;

import com.stockpulse.advisor.ReorderAdvice;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "reorder_suggestions")
public class ReorderSuggestion extends Suggestion {

    @Column(nullable = false)
    private int currentStock;

    @Column(nullable = false)
    private int recommendedQuantity;

    @Column(nullable = false)
    private int leadTimeDays;

    /** Sprint 2: preferred supplier from the supplier catalog. */
    private String supplierId;

    protected ReorderSuggestion() {
    }

    public ReorderSuggestion(Product product, int stockAtAdvice, TriggerReason trigger,
                             String strategyName, ReorderAdvice advice) {
        super(product, trigger, advice.confidence(), advice.reasoning(), advice.generatedBy(),
                strategyName, advice.fallbackReason());
        this.currentStock = stockAtAdvice;
        this.recommendedQuantity = advice.recommendedQuantity();
        this.leadTimeDays = advice.leadTimeDays();
        this.supplierId = advice.supplierId();
    }

    public int getCurrentStock() { return currentStock; }
    public int getRecommendedQuantity() { return recommendedQuantity; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public String getSupplierId() { return supplierId; }
}
