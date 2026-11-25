package com.stockpulse.domain;

public enum Category {
    ELECTRONICS(14),
    APPAREL(10),
    HOME(7);

    private final int defaultLeadTimeDays;

    Category(int defaultLeadTimeDays) {
        this.defaultLeadTimeDays = defaultLeadTimeDays;
    }

    public int defaultLeadTimeDays() {
        return defaultLeadTimeDays;
    }
}
