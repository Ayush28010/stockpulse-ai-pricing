package com.stockpulse.advisor;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Never recommend below cost x (1 + marginFloorPct). Inactive until a product has a costPrice (sprint 2 data). */
@Component
@Order(10)
public class MarginFloorGuardrail implements PriceGuardrail {

    @Override
    public PricingAdvice apply(ProductContext ctx, PricingAdvice advice) {
        if (ctx.costPrice() == null) {
            return advice;
        }
        BigDecimal floorPct = ctx.marginFloorPct() == null ? BigDecimal.ZERO : ctx.marginFloorPct();
        BigDecimal floor = ctx.costPrice()
                .multiply(BigDecimal.ONE.add(floorPct.movePointLeft(2)))
                .setScale(2, RoundingMode.CEILING);
        if (advice.recommendedPrice().compareTo(floor) >= 0) {
            return advice;
        }
        return advice.withAdjustedPrice(floor, ctx.currentPrice(),
                "Raised to margin floor " + floor + " (cost " + ctx.costPrice() + " + "
                        + floorPct.stripTrailingZeros().toPlainString() + "%).");
    }
}
