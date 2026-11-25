package com.stockpulse.domain;

import com.stockpulse.agent.ChangeSource;
import com.stockpulse.agent.TriggerEvaluator;
import org.junit.jupiter.api.Test;

import static com.stockpulse.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class StateMachineTest {

    @Test
    void productStatusIsDerivedFromFacts() {
        assertThat(ProductStatus.derive(0, true)).isEqualTo(ProductStatus.OUT_OF_STOCK);
        assertThat(ProductStatus.derive(5, true)).isEqualTo(ProductStatus.PRICE_REVIEW_PENDING);
        assertThat(ProductStatus.derive(5, false)).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void suggestionTerminalStatesAreFinal() {
        assertThat(SuggestionStatus.PENDING.canTransitionTo(SuggestionStatus.ACCEPTED)).isTrue();
        assertThat(SuggestionStatus.PENDING.canTransitionTo(SuggestionStatus.REJECTED)).isTrue();
        assertThat(SuggestionStatus.ACCEPTED.canTransitionTo(SuggestionStatus.REJECTED)).isFalse();
        assertThat(SuggestionStatus.REJECTED.canTransitionTo(SuggestionStatus.ACCEPTED)).isFalse();
        assertThat(SuggestionStatus.PENDING.canTransitionTo(SuggestionStatus.PENDING)).isFalse();
    }

    @Test
    void triggersFireIndependentlyAndSpikeOnlyOnOrders() {
        TriggerEvaluator evaluator = new TriggerEvaluator();
        var lowAndSpiking = ctx("10.00", 5, 12, 30, 7.0);
        assertThat(evaluator.evaluate(lowAndSpiking, ChangeSource.ORDER_PLACED))
                .containsExactly(TriggerReason.INVENTORY_LOW, TriggerReason.DEMAND_SPIKE);
        assertThat(evaluator.evaluate(lowAndSpiking, ChangeSource.STOCK_UPDATED))
                .containsExactly(TriggerReason.INVENTORY_LOW);
        assertThat(evaluator.evaluate(lowAndSpiking, ChangeSource.SEEDED)).containsExactly(TriggerReason.INITIAL);
        assertThat(evaluator.evaluate(ctx("10.00", 50, 12, 3, 7.0), ChangeSource.ORDER_PLACED)).isEmpty();
    }
}
