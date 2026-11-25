package com.stockpulse;

import com.stockpulse.advisor.ProductContext;
import com.stockpulse.domain.Category;

import java.math.BigDecimal;

public final class Fixtures {

    private Fixtures() {
    }

    /** Category average 7.0 and spike multiplier 3.0 unless overridden. */
    public static ProductContext ctx(String price, int stock, int threshold, int velocity) {
        return ctx(price, stock, threshold, velocity, 7.0);
    }

    public static ProductContext ctx(String price, int stock, int threshold, int velocity, double categoryAverage) {
        return new ProductContext("PRD-T", "SKU-T", "Test Hoodie", Category.APPAREL, new BigDecimal(price),
                stock, threshold, velocity, categoryAverage, 2, 3.0, null, null, null);
    }

    public static ProductContext withCost(ProductContext c, String cost, String marginFloorPct) {
        return new ProductContext(c.productId(), c.sku(), c.name(), c.category(), c.currentPrice(), c.stockLevel(),
                c.reorderThreshold(), c.demandVelocity(), c.categoryAverageVelocity(), c.peerCount(),
                c.spikeMultiplier(), new BigDecimal(cost), marginFloorPct == null ? null : new BigDecimal(marginFloorPct),
                c.supplierId());
    }
}
