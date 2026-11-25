package com.stockpulse.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from, 1, RoundingMode.HALF_UP);
    }
}
