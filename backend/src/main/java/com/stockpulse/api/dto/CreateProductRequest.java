package com.stockpulse.api.dto;

import com.stockpulse.domain.Category;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(min = 3, max = 40)
        @Pattern(regexp = "[A-Z0-9-]+", message = "must contain only A-Z, 0-9 and '-'")
        String sku,

        @NotBlank @Size(max = 120)
        String name,

        @NotNull
        Category category,

        @NotNull @DecimalMin("0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
        BigDecimal currentPrice,

        @NotNull @Min(0) @Max(1_000_000)
        Integer stockLevel,

        @NotNull @Min(0) @Max(1_000_000)
        Integer reorderThreshold,

        // Sprint 2 extension fields, optional today.
        @DecimalMin("0.01") @DecimalMax("999999.99") @Digits(integer = 6, fraction = 2)
        BigDecimal costPrice,

        @DecimalMin("0") @DecimalMax("100")
        BigDecimal marginFloorPct,

        @Size(max = 40)
        String supplierId) {
}
