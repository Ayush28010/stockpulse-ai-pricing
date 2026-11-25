package com.stockpulse.advisor;

import com.stockpulse.common.Money;
import com.stockpulse.config.GuardrailProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/** Soft band: large but plausible changes are kept and flagged for extra scrutiny instead of being rejected. */
@Component
@Order(100)
public class LargeChangeFlagGuardrail implements PriceGuardrail {

    private final GuardrailProperties limits;

    public LargeChangeFlagGuardrail(GuardrailProperties limits) {
        this.limits = limits;
    }

    @Override
    public PricingAdvice apply(ProductContext ctx, PricingAdvice advice) {
        BigDecimal pct = Money.percentChange(ctx.currentPrice(), advice.recommendedPrice());
        if (pct.abs().doubleValue() > limits.flagChangePct()) {
            return advice.withFlag(String.format(Locale.ROOT,
                    "Change of %s%% exceeds the +/-%.0f%% review band.", pct.toPlainString(), limits.flagChangePct()));
        }
        return advice;
    }
}
