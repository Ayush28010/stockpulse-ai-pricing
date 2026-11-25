package com.stockpulse.advisor;

import com.stockpulse.config.RuleProperties;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;

import static com.stockpulse.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedStrategyTest {

    private final RuleBasedStrategy rules = new RuleBasedStrategy(new RuleProperties(2.0, 10, 5, 3));

    @Test
    void belowThresholdRaisesPriceTenPercent() {
        PricingAdvice p = rules.pricing(ctx("24.99", 8, 15, 3));
        assertThat(p.recommendedPrice()).isEqualByComparingTo("27.49");
        assertThat(p.direction()).isEqualTo(PriceDirection.INCREASE);
        assertThat(p.tactic()).isEqualTo(PricingTactic.PROTECT_INVENTORY);
        assertThat(p.generatedBy()).isEqualTo(GeneratedBy.RULE);
    }

    @Test
    void velocityAboveTwiceCategoryAverageRaisesFivePercent() {
        PricingAdvice p = rules.pricing(ctx("100.00", 50, 10, 15, 7.0));
        assertThat(p.recommendedPrice()).isEqualByComparingTo("105.00");
        assertThat(p.tactic()).isEqualTo(PricingTactic.CAPITALIZE_ON_DEMAND);
    }

    @Test
    void otherwiseHolds() {
        PricingAdvice p = rules.pricing(ctx("39.99", 55, 20, 2, 13.5));
        assertThat(p.recommendedPrice()).isEqualByComparingTo("39.99");
        assertThat(p.direction()).isEqualTo(PriceDirection.HOLD);
    }

    @Test
    void reorderIsThreeTimesThresholdMinusStockWithMinimumOne() {
        assertThat(rules.reorder(ctx("10.00", 8, 15, 1)).recommendedQuantity()).isEqualTo(37);
        assertThat(rules.reorder(ctx("10.00", 500, 15, 1)).recommendedQuantity()).isEqualTo(1);
    }

    @Test
    void onlyWantedHalvesAreProduced() {
        Advice a = rules.advise(ctx("10.00", 8, 15, 1), TriggerReason.MANUAL, EnumSet.of(SuggestionType.REORDER));
        assertThat(a.pricing()).isNull();
        assertThat(a.reorder()).isNotNull();
    }

    @Test
    void fallbackIsMarkedWithReason() {
        Advice a = rules.fallback(ctx("10.00", 8, 15, 1), EnumSet.allOf(SuggestionType.class), "LLM down");
        assertThat(a.pricing().generatedBy()).isEqualTo(GeneratedBy.RULE_FALLBACK);
        assertThat(a.reorder().fallbackReason()).isEqualTo("LLM down");
        assertThat(a.pricing().recommendedPrice()).isEqualByComparingTo(new BigDecimal("11.00"));
    }
}
