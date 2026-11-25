package com.stockpulse.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.advisor.PricingAdvice;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.advisor.ReorderAdvice;
import com.stockpulse.common.Money;
import com.stockpulse.config.GuardrailProperties;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns untrusted LLM JSON into advice or throws {@link InvalidAiOutputException} (caller falls back to rules).
 * Direction is always derived from the numbers, never taken from the model.
 */
@Component
public class AiAdviceValidator {

    static final int MAX_REASONING = 2000;
    private static final int MAX_LEAD_TIME_DAYS = 180;

    private final GuardrailProperties limits;

    public AiAdviceValidator(GuardrailProperties limits) {
        this.limits = limits;
    }

    public PricingAdvice validatePricing(ProductContext ctx, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidAiOutputException("missing 'pricing' object");
        }
        BigDecimal current = ctx.currentPrice();
        BigDecimal raw = decimal(node, "recommendedPrice");
        if (raw.signum() <= 0) {
            throw new InvalidAiOutputException("recommendedPrice must be positive, got " + raw.toPlainString());
        }
        BigDecimal price = Money.round(raw);
        double ratio = price.doubleValue() / current.doubleValue();
        if (ratio < limits.minPriceRatio() || ratio > limits.maxPriceRatio()) {
            throw new InvalidAiOutputException(String.format(Locale.ROOT,
                    "recommendedPrice %s is %.2fx current price %s; allowed range is %.2fx-%.2fx",
                    price.toPlainString(), ratio, current.toPlainString(), limits.minPriceRatio(), limits.maxPriceRatio()));
        }
        double confidence = confidence(node);
        String reasoning = reasoning(node);

        List<String> notes = new ArrayList<>();
        PriceDirection direction = PriceDirection.of(current, price);
        PricingTactic tactic = tactic(node.get("tactic"));
        if (tactic == null) {
            tactic = inferTactic(direction, ctx);
            notes.add("Model omitted a valid tactic; inferred " + tactic + ".");
        }
        if (tactic == PricingTactic.HOLD && direction != PriceDirection.HOLD) {
            notes.add("Model chose HOLD but proposed " + price.toPlainString() + "; price held at current.");
            price = current;
            direction = PriceDirection.HOLD;
        } else if (!tactic.isConsistentWith(direction)) {
            notes.add("Tactic " + tactic + " is inconsistent with a " + direction + " in price; review the reasoning.");
        }
        if (tactic == PricingTactic.CLEARANCE && ctx.stockLevel() == 0) {
            notes.add("CLEARANCE on an out-of-stock item: there are no units to move; this sets the relaunch price.");
        }
        return new PricingAdvice(price, direction, tactic, confidence, reasoning, GeneratedBy.AI, null,
                !notes.isEmpty(), notes.isEmpty() ? null : String.join(" ", notes));
    }

    public ReorderAdvice validateReorder(ProductContext ctx, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidAiOutputException("missing 'reorder' object");
        }
        BigDecimal quantity = decimal(node, "recommendedQuantity");
        if (quantity.stripTrailingZeros().scale() > 0) {
            throw new InvalidAiOutputException("recommendedQuantity must be a whole number, got " + quantity.toPlainString());
        }
        if (quantity.signum() <= 0) {
            throw new InvalidAiOutputException("recommendedQuantity must be positive, got " + quantity.toPlainString());
        }
        int max = limits.maxReorderQuantity(ctx.reorderThreshold(), ctx.demandVelocity());
        if (quantity.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new InvalidAiOutputException("recommendedQuantity " + quantity.toPlainString()
                    + " exceeds the sanity cap of " + max + " (max of " + limits.maxReorderThresholdMultiple()
                    + "x threshold or " + limits.maxReorderDaysOfCover() + " days of cover)");
        }
        return new ReorderAdvice(quantity.intValueExact(), leadTime(node, ctx), confidence(node), reasoning(node),
                ctx.supplierId(), GeneratedBy.AI, null);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new InvalidAiOutputException("missing '" + field + "'");
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText().trim());
            } catch (NumberFormatException e) {
                throw new InvalidAiOutputException("'" + field + "' is not a number: " + value.asText());
            }
        }
        throw new InvalidAiOutputException("'" + field + "' is not a number");
    }

    private static double confidence(JsonNode node) {
        double c = decimal(node, "confidence").doubleValue();
        if (!Double.isFinite(c)) {
            throw new InvalidAiOutputException("confidence is not finite");
        }
        if (c > 1 && c <= 100) {
            c = c / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, c));
    }

    private static String reasoning(JsonNode node) {
        JsonNode value = node.get("reasoning");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidAiOutputException("missing 'reasoning'");
        }
        String text = value.asText().trim();
        return text.length() > MAX_REASONING ? text.substring(0, MAX_REASONING) : text;
    }

    private static int leadTime(JsonNode node, ProductContext ctx) {
        JsonNode value = node.get("leadTimeDays");
        if (value != null && value.canConvertToInt()) {
            int days = value.asInt();
            if (days >= 1 && days <= MAX_LEAD_TIME_DAYS) {
                return days;
            }
        }
        return ctx.category().defaultLeadTimeDays();
    }

    private static PricingTactic tactic(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return PricingTactic.valueOf(value.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PricingTactic inferTactic(PriceDirection direction, ProductContext ctx) {
        return switch (direction) {
            case INCREASE -> ctx.inventoryLow() ? PricingTactic.PROTECT_INVENTORY : PricingTactic.CAPITALIZE_ON_DEMAND;
            case DECREASE -> PricingTactic.CLEARANCE;
            case HOLD -> PricingTactic.HOLD;
        };
    }
}
