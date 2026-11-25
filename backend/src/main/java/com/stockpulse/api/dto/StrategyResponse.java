package com.stockpulse.api.dto;

import java.util.List;

public record StrategyResponse(String active, String fallback, List<StrategyInfo> available,
                               boolean llmConfigured, String llmModel) {

    public record StrategyInfo(String name, String description) {
    }
}
