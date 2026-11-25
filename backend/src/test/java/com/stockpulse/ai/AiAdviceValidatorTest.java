package com.stockpulse.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.advisor.PricingAdvice;
import com.stockpulse.advisor.ReorderAdvice;
import com.stockpulse.config.GuardrailProperties;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.stockpulse.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAdviceValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiAdviceValidator validator = new AiAdviceValidator(new GuardrailProperties(0.25, 4.0, 30, 20, 60));

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void validPricingDerivesDirectionServerSide() throws Exception {
        PricingAdvice p = validator.validatePricing(ctx("24.99", 8, 15, 12),
                json("{\"tactic\":\"PROTECT_INVENTORY\",\"recommendedPrice\":27.494,\"confidence\":0.8,\"reasoning\":\"scarce\",\"direction\":\"DECREASE\"}"));
        assertThat(p.recommendedPrice()).isEqualByComparingTo("27.49");
        assertThat(p.direction()).isEqualTo(PriceDirection.INCREASE);
        assertThat(p.flagged()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "0.001", "999999", "5.00", "120.00"})
    void absurdPricesAreRejected(String price) {
        assertThatThrownBy(() -> validator.validatePricing(ctx("24.99", 8, 15, 12),
                json("{\"tactic\":\"HOLD\",\"recommendedPrice\":" + price + ",\"confidence\":0.8,\"reasoning\":\"x\"}")))
                .isInstanceOf(InvalidAiOutputException.class);
    }

    @Test
    void missingFieldsAreRejected() {
        assertThatThrownBy(() -> validator.validatePricing(ctx("24.99", 8, 15, 12), json("{\"recommendedPrice\":25}")))
                .isInstanceOf(InvalidAiOutputException.class).hasMessageContaining("confidence");
        assertThatThrownBy(() -> validator.validatePricing(ctx("24.99", 8, 15, 12), null))
                .isInstanceOf(InvalidAiOutputException.class);
        assertThatThrownBy(() -> validator.validatePricing(ctx("24.99", 8, 15, 12),
                json("{\"recommendedPrice\":\"twenty\",\"confidence\":0.5,\"reasoning\":\"x\"}")))
                .isInstanceOf(InvalidAiOutputException.class);
    }

    @Test
    void holdTacticWithDifferentPriceIsHeldAndFlagged() throws Exception {
        PricingAdvice p = validator.validatePricing(ctx("24.99", 8, 15, 12),
                json("{\"tactic\":\"HOLD\",\"recommendedPrice\":26.00,\"confidence\":0.6,\"reasoning\":\"x\"}"));
        assertThat(p.recommendedPrice()).isEqualByComparingTo("24.99");
        assertThat(p.direction()).isEqualTo(PriceDirection.HOLD);
        assertThat(p.flagged()).isTrue();
    }

    @Test
    void inconsistentTacticIsFlagged() throws Exception {
        PricingAdvice p = validator.validatePricing(ctx("24.99", 8, 15, 12),
                json("{\"tactic\":\"CLEARANCE\",\"recommendedPrice\":26.00,\"confidence\":0.6,\"reasoning\":\"x\"}"));
        assertThat(p.flagged()).isTrue();
        assertThat(p.tactic()).isEqualTo(PricingTactic.CLEARANCE);
    }

    @Test
    void confidenceAsPercentIsNormalisedAndOutOfRangeClamped() throws Exception {
        assertThat(validator.validatePricing(ctx("10.00", 8, 15, 12),
                json("{\"tactic\":\"HOLD\",\"recommendedPrice\":10,\"confidence\":82,\"reasoning\":\"x\"}")).confidence()).isEqualTo(0.82);
        assertThat(validator.validatePricing(ctx("10.00", 8, 15, 12),
                json("{\"tactic\":\"HOLD\",\"recommendedPrice\":10,\"confidence\":-3,\"reasoning\":\"x\"}")).confidence()).isEqualTo(0.0);
    }

    @Test
    void reorderMustBePositiveWholeNumberWithinCap() throws Exception {
        ReorderAdvice r = validator.validateReorder(ctx("10.00", 8, 15, 12),
                json("{\"recommendedQuantity\":150,\"leadTimeDays\":7,\"confidence\":0.7,\"reasoning\":\"x\"}"));
        assertThat(r.recommendedQuantity()).isEqualTo(150);
        assertThat(r.leadTimeDays()).isEqualTo(7);

        for (String bad : new String[]{"0", "-4", "12.5", "100000"}) {
            assertThatThrownBy(() -> validator.validateReorder(ctx("10.00", 8, 15, 12),
                    json("{\"recommendedQuantity\":" + bad + ",\"confidence\":0.7,\"reasoning\":\"x\"}")))
                    .as(bad).isInstanceOf(InvalidAiOutputException.class);
        }
    }

    @Test
    void reorderCapScalesWithDemand() throws Exception {
        // threshold 12 -> 240 by threshold rule, but 15/day x 60 days = 900 allows a fast seller's sensible 255
        ReorderAdvice r = validator.validateReorder(ctx("54.99", 11, 12, 15),
                json("{\"recommendedQuantity\":255,\"confidence\":0.7,\"reasoning\":\"x\"}"));
        assertThat(r.recommendedQuantity()).isEqualTo(255);
    }

    @Test
    void invalidLeadTimeFallsBackToCategoryDefault() throws Exception {
        ReorderAdvice r = validator.validateReorder(ctx("10.00", 8, 15, 12),
                json("{\"recommendedQuantity\":40,\"leadTimeDays\":-2,\"confidence\":0.7,\"reasoning\":\"x\"}"));
        assertThat(r.leadTimeDays()).isEqualTo(10);
    }
}
