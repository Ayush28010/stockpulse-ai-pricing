package com.stockpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("stockpulse.rule")
public record RuleProperties(
        @DefaultValue("2.0") double demandMultiplier,
        @DefaultValue("10") int lowStockIncreasePct,
        @DefaultValue("5") int demandIncreasePct,
        @DefaultValue("3") int reorderMultiplier) {
}
