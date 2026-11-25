package com.stockpulse.ai;

import com.stockpulse.advisor.Advice;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.advisor.RuleBasedStrategy;
import com.stockpulse.config.RuleProperties;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static com.stockpulse.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptFactoryTest {

    private final PromptFactory factory = new PromptFactory();
    private final RuleBasedStrategy rules = new RuleBasedStrategy(new RuleProperties(2.0, 10, 5, 3));

    private Prompt build(ProductContext c, TriggerReason t) {
        Advice baseline = rules.advise(c, t, EnumSet.allOf(SuggestionType.class));
        return factory.build(c, t, baseline, false);
    }

    @Test
    void inventoryLowPromptCarriesStockVsThresholdAndTradeoffs() {
        Prompt p = build(ctx("24.99", 8, 15, 12, 8.5), TriggerReason.INVENTORY_LOW);
        assertThat(p.template()).isEqualTo(PromptFactory.INVENTORY_LOW);
        assertThat(p.user()).contains("INVENTORY LOW", "Stock on hand: 8 units", "Reorder threshold: 15 units",
                "Shortfall vs threshold: 7", "PROTECT_INVENTORY", "CLEARANCE", "Category average", "8.5");
        assertThat(p.user()).doesNotContain("{{");
    }

    @Test
    void demandSpikePromptIsADifferentDocumentLedByVelocity() {
        ProductContext c = ctx("54.99", 40, 12, 25, 7.0);
        Prompt spike = build(c, TriggerReason.DEMAND_SPIKE);
        Prompt low = build(c, TriggerReason.INVENTORY_LOW);
        assertThat(spike.template()).isEqualTo(PromptFactory.DEMAND_SPIKE);
        assertThat(spike.user()).contains("DEMAND SPIKE", "25 orders in the last 24h", "3.6x the category average",
                "spike threshold is 3.0x (21.0", "TRANSIENT", "SUSTAINED", "CAPITALIZE_ON_DEMAND");
        assertThat(spike.user()).isNotEqualTo(low.user());
        assertThat(spike.user().indexOf("DEMAND SIGNAL")).isLessThan(spike.user().indexOf("STOCK POSITION"));
    }

    @Test
    void lowStockPromptMentionsConcurrentSpike() {
        Prompt p = build(ctx("54.99", 5, 12, 30, 7.0), TriggerReason.INVENTORY_LOW);
        assertThat(p.user()).contains("ADDITIONAL SIGNAL: demand is ALSO spiking");
    }

    @Test
    void outOfStockPromptRulesOutClearance() {
        Prompt p = build(ctx("59.99", 0, 15, 0, 4.0), TriggerReason.INITIAL);
        assertThat(p.template()).isEqualTo(PromptFactory.INVENTORY_LOW);
        assertThat(p.user()).contains("OUT OF STOCK: there are no units to clear");
    }

    @Test
    void initialPicksTemplateFromSituation() {
        assertThat(PromptFactory.templateFor(ctx("10.00", 50, 10, 30, 7.0), TriggerReason.INITIAL))
                .isEqualTo(PromptFactory.DEMAND_SPIKE);
        assertThat(PromptFactory.templateFor(ctx("10.00", 50, 10, 1, 7.0), TriggerReason.MANUAL))
                .isEqualTo(PromptFactory.MANUAL_REVIEW);
    }

    @Test
    void streamingPromptAsksForMarker() {
        ProductContext c = ctx("24.99", 8, 15, 12);
        Prompt p = factory.build(c, TriggerReason.MANUAL, rules.advise(c, TriggerReason.MANUAL, EnumSet.allOf(SuggestionType.class)), true);
        assertThat(p.system()).contains("===JSON===");
    }

    @Test
    void unknownPlaceholderFailsLoudly() {
        assertThatThrownBy(() -> PromptFactory.render("hi {{nope}}", Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
