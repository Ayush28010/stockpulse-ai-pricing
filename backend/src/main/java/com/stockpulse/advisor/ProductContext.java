package com.stockpulse.advisor;

import com.stockpulse.domain.Category;

import java.math.BigDecimal;

/**
 * Immutable snapshot a strategy reasons over. Strategies never touch repositories,
 * so the same contract serves HTTP and async callers and is unit-testable without Spring.
 */
public record ProductContext(
        String productId,
        String sku,
        String name,
        Category category,
        BigDecimal currentPrice,
        int stockLevel,
        int reorderThreshold,
        int demandVelocity,
        double categoryAverageVelocity,
        int peerCount,
        double spikeMultiplier,
        BigDecimal costPrice,
        BigDecimal marginFloorPct,
        String supplierId) {

    /** Floor for the peer average so sparse categories do not divide by zero or make every sale a "spike". */
    public static final double MIN_CATEGORY_AVERAGE = 1.0;

    public static double flooredAverage(Double average) {
        return average == null ? MIN_CATEGORY_AVERAGE : Math.max(MIN_CATEGORY_AVERAGE, average);
    }

    public boolean inventoryLow() {
        return stockLevel < reorderThreshold;
    }

    public double velocityRatio() {
        return demandVelocity / categoryAverageVelocity;
    }

    public double spikeThreshold() {
        return spikeMultiplier * categoryAverageVelocity;
    }

    public boolean demandSpike() {
        return demandVelocity > spikeThreshold();
    }

    /** Days until stock-out if every order is one unit; null when there is no demand. */
    public Double daysOfCover() {
        return demandVelocity == 0 ? null : (double) stockLevel / demandVelocity;
    }
}
