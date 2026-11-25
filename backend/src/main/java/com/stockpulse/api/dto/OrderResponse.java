package com.stockpulse.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long orderId, int quantity, BigDecimal unitPrice, Instant createdAt, ProductResponse product) {
}
