package com.stockpulse.advisor;

/** Unified output of one strategy invocation. A half is null when it was not requested. */
public record Advice(PricingAdvice pricing, ReorderAdvice reorder) {
}
