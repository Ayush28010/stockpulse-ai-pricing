package com.stockpulse.domain;

/**
 * Product lifecycle. Status is derived from facts (stock, pending pricing) by {@link #derive},
 * and every change goes through {@link #canTransitionTo}.
 * <pre>
 * ACTIVE ──pricing suggestion queued──▶ PRICE_REVIEW_PENDING ──all pricing decided──▶ ACTIVE
 *   any ──stock hits 0──▶ OUT_OF_STOCK ──restocked──▶ ACTIVE | PRICE_REVIEW_PENDING
 * </pre>
 */
public enum ProductStatus {
    ACTIVE,
    PRICE_REVIEW_PENDING,
    OUT_OF_STOCK;

    public static ProductStatus derive(int stockLevel, boolean hasPendingPricing) {
        if (stockLevel == 0) {
            return OUT_OF_STOCK;
        }
        return hasPendingPricing ? PRICE_REVIEW_PENDING : ACTIVE;
    }

    public boolean canTransitionTo(ProductStatus next) {
        return switch (this) {
            case ACTIVE -> next == PRICE_REVIEW_PENDING || next == OUT_OF_STOCK;
            case PRICE_REVIEW_PENDING -> next == ACTIVE || next == OUT_OF_STOCK;
            case OUT_OF_STOCK -> next == ACTIVE || next == PRICE_REVIEW_PENDING;
        };
    }
}
