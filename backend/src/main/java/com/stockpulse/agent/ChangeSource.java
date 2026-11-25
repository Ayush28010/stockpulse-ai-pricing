package com.stockpulse.agent;

public enum ChangeSource {
    CREATED,
    SEEDED,
    STOCK_UPDATED,
    ORDER_PLACED,
    REORDER_RECEIVED;

    public boolean isInitial() {
        return this == CREATED || this == SEEDED;
    }
}
