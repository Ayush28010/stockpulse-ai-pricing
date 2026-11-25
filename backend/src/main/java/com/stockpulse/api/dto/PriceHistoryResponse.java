package com.stockpulse.api.dto;

import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.PriceHistory;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceHistoryResponse(Long id, BigDecimal oldPrice, BigDecimal newPrice, Long pricingSuggestionId,
                                   TriggerReason triggerReason, Instant changedAt) {

    public static PriceHistoryResponse from(PriceHistory h) {
        return new PriceHistoryResponse(h.getId(), h.getOldPrice(), h.getNewPrice(), h.getPricingSuggestionId(),
                h.getTriggerReason(), h.getChangedAt());
    }
}
