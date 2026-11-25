package com.stockpulse.domain;

/** The merchandising decision behind a price recommendation; chosen by the LLM, validated server-side. */
public enum PricingTactic {
    PROTECT_INVENTORY,
    CLEARANCE,
    CAPITALIZE_ON_DEMAND,
    HOLD;

    public boolean isConsistentWith(PriceDirection direction) {
        return switch (this) {
            case PROTECT_INVENTORY, CAPITALIZE_ON_DEMAND -> direction == PriceDirection.INCREASE;
            case CLEARANCE -> direction == PriceDirection.DECREASE;
            case HOLD -> direction == PriceDirection.HOLD;
        };
    }
}
