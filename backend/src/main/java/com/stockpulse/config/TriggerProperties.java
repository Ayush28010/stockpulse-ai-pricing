package com.stockpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("stockpulse.trigger")
public record TriggerProperties(@DefaultValue("3.0") double spikeMultiplier) {
}
