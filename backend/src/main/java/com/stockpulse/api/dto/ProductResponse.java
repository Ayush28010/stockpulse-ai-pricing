package com.stockpulse.api.dto;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.ProductStatus;
import com.stockpulse.product.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record ProductResponse(
        String id,
        String sku,
        String name,
        Category category,
        BigDecimal currentPrice,
        int stockLevel,
        int reorderThreshold,
        boolean belowReorderThreshold,
        int demandVelocity,
        double categoryAverageVelocity,
        double velocityRatio,
        boolean demandSpike,
        ProductStatus status,
        BigDecimal costPrice,
        BigDecimal marginFloorPct,
        BigDecimal marginPct,
        String supplierId,
        long pendingPricingCount,
        long pendingReorderCount,
        Instant updatedAt) {

    public static ProductResponse from(Product p, double categoryAverage, double spikeMultiplier,
                                       long pendingPricing, long pendingReorder) {
        double ratio = p.getDemandVelocity() / categoryAverage;
        return new ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getCurrentPrice(),
                p.getStockLevel(), p.getReorderThreshold(), p.isBelowReorderThreshold(),
                p.getDemandVelocity(), round1(categoryAverage), round1(ratio),
                p.getDemandVelocity() > spikeMultiplier * categoryAverage,
                p.getStatus(), p.getCostPrice(), p.getMarginFloorPct(), margin(p), p.getSupplierId(),
                pendingPricing, pendingReorder, p.getUpdatedAt());
    }

    private static BigDecimal margin(Product p) {
        if (p.getCostPrice() == null) {
            return null;
        }
        return p.getCurrentPrice().subtract(p.getCostPrice())
                .multiply(BigDecimal.valueOf(100))
                .divide(p.getCurrentPrice(), 1, RoundingMode.HALF_UP);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
