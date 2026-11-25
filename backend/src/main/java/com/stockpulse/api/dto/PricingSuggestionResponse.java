package com.stockpulse.api.dto;

import com.stockpulse.common.Money;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.suggestion.PricingSuggestion;

import java.math.BigDecimal;
import java.time.Instant;

public record PricingSuggestionResponse(
        Long id,
        String productId,
        String productSku,
        String productName,
        BigDecimal currentPrice,
        BigDecimal recommendedPrice,
        BigDecimal changePct,
        PriceDirection direction,
        PricingTactic tactic,
        double confidence,
        String reasoning,
        SuggestionStatus status,
        TriggerReason triggerReason,
        GeneratedBy generatedBy,
        String strategyName,
        String fallbackReason,
        boolean flagged,
        String flagNote,
        String decisionNote,
        Instant createdAt,
        Instant decidedAt) {

    public static PricingSuggestionResponse from(PricingSuggestion s) {
        return new PricingSuggestionResponse(
                s.getId(), s.getProduct().getId(), s.getProduct().getSku(), s.getProduct().getName(),
                s.getCurrentPrice(), s.getRecommendedPrice(),
                Money.percentChange(s.getCurrentPrice(), s.getRecommendedPrice()),
                s.getDirection(), s.getTactic(), s.getConfidence(), s.getReasoning(), s.getStatus(),
                s.getTriggerReason(), s.getGeneratedBy(), s.getStrategyName(), s.getFallbackReason(),
                s.isFlagged(), s.getFlagNote(), s.getDecisionNote(), s.getCreatedAt(), s.getDecidedAt());
    }
}
