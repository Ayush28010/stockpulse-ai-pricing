package com.stockpulse.api.dto;

import jakarta.validation.constraints.NotBlank;

public record StrategyRequest(@NotBlank String strategy) {
}
