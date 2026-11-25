package com.stockpulse.advisor;

/** What the advisor hands to persistence: the strategy that ran plus guarded, never-null wanted halves. */
public record AdvisorResult(String strategyName, PricingAdvice pricing, ReorderAdvice reorder) {
}
