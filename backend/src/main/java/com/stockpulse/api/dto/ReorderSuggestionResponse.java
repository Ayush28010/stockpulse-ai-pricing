package com.stockpulse.api.dto;

import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.suggestion.ReorderSuggestion;

import java.time.Instant;

public record ReorderSuggestionResponse(
        Long id,
        String productId,
        String productSku,
        String productName,
        int currentStock,
        int recommendedQuantity,
        int leadTimeDays,
        double confidence,
        String reasoning,
        SuggestionStatus status,
        TriggerReason triggerReason,
        GeneratedBy generatedBy,
        String strategyName,
        String fallbackReason,
        String supplierId,
        String decisionNote,
        Instant createdAt,
        Instant decidedAt) {

    public static ReorderSuggestionResponse from(ReorderSuggestion s) {
        return new ReorderSuggestionResponse(
                s.getId(), s.getProduct().getId(), s.getProduct().getSku(), s.getProduct().getName(),
                s.getCurrentStock(), s.getRecommendedQuantity(), s.getLeadTimeDays(), s.getConfidence(),
                s.getReasoning(), s.getStatus(), s.getTriggerReason(), s.getGeneratedBy(), s.getStrategyName(),
                s.getFallbackReason(), s.getSupplierId(), s.getDecisionNote(), s.getCreatedAt(), s.getDecidedAt());
    }
}
