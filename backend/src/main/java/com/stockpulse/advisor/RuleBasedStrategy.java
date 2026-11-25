package com.stockpulse.advisor;

import com.stockpulse.common.Money;
import com.stockpulse.config.RuleProperties;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.PriceDirection;
import com.stockpulse.domain.PricingTactic;
import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Deterministic baseline and the fallback target for every other strategy. */
@Component
public class RuleBasedStrategy implements CommerceStrategy {

    public static final String NAME = "RULE";

    private final RuleProperties rules;

    public RuleBasedStrategy(RuleProperties rules) {
        this.rules = rules;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Deterministic rules: +" + rules.lowStockIncreasePct() + "% when below threshold, +"
                + rules.demandIncreasePct() + "% when velocity > " + rules.demandMultiplier()
                + "x category average, else hold; reorder to " + rules.reorderMultiplier() + "x threshold.";
    }

    @Override
    public Advice advise(ProductContext context, TriggerReason trigger, Set<SuggestionType> wanted) {
        return new Advice(
                wanted.contains(SuggestionType.PRICING) ? pricing(context) : null,
                wanted.contains(SuggestionType.REORDER) ? reorder(context) : null);
    }

    public Advice fallback(ProductContext context, Set<SuggestionType> wanted, String reason) {
        return new Advice(
                wanted.contains(SuggestionType.PRICING) ? pricing(context).asFallback(reason) : null,
                wanted.contains(SuggestionType.REORDER) ? reorder(context).asFallback(reason) : null);
    }

    public PricingAdvice pricing(ProductContext ctx) {
        BigDecimal current = ctx.currentPrice();
        if (ctx.inventoryLow()) {
            BigDecimal price = increase(current, rules.lowStockIncreasePct());
            return new PricingAdvice(price, PriceDirection.of(current, price), PricingTactic.PROTECT_INVENTORY, 0.6,
                    String.format(Locale.ROOT,
                            "Rule: stock %d is below reorder threshold %d, so raise price %d%% to slow sell-through until replenishment arrives.",
                            ctx.stockLevel(), ctx.reorderThreshold(), rules.lowStockIncreasePct()),
                    GeneratedBy.RULE, null, false, null);
        }
        if (ctx.demandVelocity() > rules.demandMultiplier() * ctx.categoryAverageVelocity()) {
            BigDecimal price = increase(current, rules.demandIncreasePct());
            return new PricingAdvice(price, PriceDirection.of(current, price), PricingTactic.CAPITALIZE_ON_DEMAND, 0.55,
                    String.format(Locale.ROOT,
                            "Rule: velocity %d orders/24h is %.1fx the category average %.1f (above %.1fx), so raise price %d%%.",
                            ctx.demandVelocity(), ctx.velocityRatio(), ctx.categoryAverageVelocity(),
                            rules.demandMultiplier(), rules.demandIncreasePct()),
                    GeneratedBy.RULE, null, false, null);
        }
        return new PricingAdvice(current, PriceDirection.HOLD, PricingTactic.HOLD, 0.5,
                "Rule: stock is at or above threshold and demand is within the normal band for the category; hold price.",
                GeneratedBy.RULE, null, false, null);
    }

    public ReorderAdvice reorder(ProductContext ctx) {
        int target = ctx.reorderThreshold() * rules.reorderMultiplier();
        int quantity = Math.max(1, target - ctx.stockLevel());
        return new ReorderAdvice(quantity, ctx.category().defaultLeadTimeDays(), 0.6,
                String.format(Locale.ROOT,
                        "Rule: target stock %d (%dx threshold %d) minus current stock %d = %d units (minimum 1).",
                        target, rules.reorderMultiplier(), ctx.reorderThreshold(), ctx.stockLevel(), quantity),
                ctx.supplierId(), GeneratedBy.RULE, null);
    }

    private static BigDecimal increase(BigDecimal price, int pct) {
        return Money.round(price.multiply(BigDecimal.valueOf(100 + pct)).divide(BigDecimal.valueOf(100)));
    }
}
