package com.stockpulse.advisor;

import com.stockpulse.domain.GeneratedBy;

public record ReorderAdvice(
        int recommendedQuantity,
        int leadTimeDays,
        double confidence,
        String reasoning,
        String supplierId,
        GeneratedBy generatedBy,
        String fallbackReason) {

    public ReorderAdvice asFallback(String reason) {
        return new ReorderAdvice(recommendedQuantity, leadTimeDays, confidence, reasoning, supplierId,
                GeneratedBy.RULE_FALLBACK, reason);
    }
}
