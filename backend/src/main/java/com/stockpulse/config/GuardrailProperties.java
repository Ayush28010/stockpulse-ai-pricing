package com.stockpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hard bounds reject AI output (rule fallback); the soft band only flags it for the merchandiser.
 */
@ConfigurationProperties("stockpulse.guardrail")
public record GuardrailProperties(
        @DefaultValue("0.25") double minPriceRatio,
        @DefaultValue("4.0") double maxPriceRatio,
        @DefaultValue("30") double flagChangePct,
        @DefaultValue("20") int maxReorderThresholdMultiple,
        @DefaultValue("60") int maxReorderDaysOfCover) {

    /** Sanity cap for a reorder: the larger of N x threshold or N days of cover at current velocity. */
    public int maxReorderQuantity(int reorderThreshold, int demandVelocity) {
        return Math.max(maxReorderThresholdMultiple * Math.max(reorderThreshold, 1), maxReorderDaysOfCover * demandVelocity);
    }
}
