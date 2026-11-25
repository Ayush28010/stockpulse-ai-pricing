package com.stockpulse.api.dto;

import com.stockpulse.domain.SuggestionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecisionRequest(@NotNull SuggestionStatus status, @Size(max = 500) String note) {
}
