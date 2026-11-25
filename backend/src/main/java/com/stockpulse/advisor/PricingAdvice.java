package com.stockpulse.advisor;

import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;

import java.math.BigDecimal;

public record PricingAdvice(
        BigDecimal recommendedPrice,
        PriceDirection direction,
        PricingTactic tactic,
        double confidence,
        String reasoning,
        GeneratedBy generatedBy,
        String fallbackReason,
        boolean flagged,
        String flagNote) {

    public PricingAdvice asFallback(String reason) {
        return new PricingAdvice(recommendedPrice, direction, tactic, confidence, reasoning,
                GeneratedBy.RULE_FALLBACK, reason, flagged, flagNote);
    }

    public PricingAdvice withFlag(String note) {
        return new PricingAdvice(recommendedPrice, direction, tactic, confidence, reasoning,
                generatedBy, fallbackReason, true, join(flagNote, note));
    }

    public PricingAdvice withAdjustedPrice(BigDecimal newPrice, BigDecimal currentPrice, String note) {
        return new PricingAdvice(newPrice, PriceDirection.of(currentPrice, newPrice), tactic, confidence, reasoning,
                generatedBy, fallbackReason, true, join(flagNote, note));
    }

    private static String join(String existing, String note) {
        return existing == null || existing.isBlank() ? note : existing + " " + note;
    }
}
