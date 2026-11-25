package com.stockpulse.api.dto;

/** Accept/reject result: the decided suggestion plus the product state written in the same transaction. */
public record DecisionResponse<T>(T suggestion, ProductResponse product) {
}
