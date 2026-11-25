package com.stockpulse.advisor;

import com.stockpulse.config.GuardrailProperties;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.stockpulse.Fixtures.ctx;
import static com.stockpulse.Fixtures.withCost;
import static org.assertj.core.api.Assertions.assertThat;

class GuardrailTest {

    private static PricingAdvice advice(String price, PriceDirection direction, PricingTactic tactic) {
        return new PricingAdvice(new BigDecimal(price), direction, tactic, 0.8, "r", GeneratedBy.AI, null, false, null);
    }

    @Test
    void marginFloorRaisesPriceAndFlags() {
        ProductContext c = withCost(ctx("50.00", 5, 10, 1), "40.00", "10");
        PricingAdvice out = new MarginFloorGuardrail().apply(c, advice("35.00", PriceDirection.DECREASE, PricingTactic.CLEARANCE));
        assertThat(out.recommendedPrice()).isEqualByComparingTo("44.00");
        assertThat(out.direction()).isEqualTo(PriceDirection.DECREASE);
        assertThat(out.flagged()).isTrue();
        assertThat(out.flagNote()).contains("margin floor");
    }

    @Test
    void marginFloorInactiveWithoutCost() {
        PricingAdvice in = advice("10.00", PriceDirection.DECREASE, PricingTactic.CLEARANCE);
        assertThat(new MarginFloorGuardrail().apply(ctx("50.00", 5, 10, 1), in)).isSameAs(in);
    }

    @Test
    void largeChangeIsFlaggedNotRejected() {
        LargeChangeFlagGuardrail g = new LargeChangeFlagGuardrail(new GuardrailProperties(0.25, 4.0, 30, 20, 60));
        PricingAdvice out = g.apply(ctx("100.00", 5, 10, 1), advice("140.00", PriceDirection.INCREASE, PricingTactic.PROTECT_INVENTORY));
        assertThat(out.flagged()).isTrue();
        assertThat(out.recommendedPrice()).isEqualByComparingTo("140.00");
        assertThat(g.apply(ctx("100.00", 5, 10, 1), advice("110.00", PriceDirection.INCREASE, PricingTactic.PROTECT_INVENTORY)).flagged()).isFalse();
    }
}
