package com.stockpulse.domain;

import java.math.BigDecimal;

public enum PriceDirection {
    INCREASE,
    DECREASE,
    HOLD;

    public static PriceDirection of(BigDecimal current, BigDecimal recommended) {
        int cmp = recommended.compareTo(current);
        if (cmp > 0) {
            return INCREASE;
        }
        return cmp < 0 ? DECREASE : HOLD;
    }
}
