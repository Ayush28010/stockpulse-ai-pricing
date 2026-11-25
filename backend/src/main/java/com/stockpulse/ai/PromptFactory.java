package com.stockpulse.ai;

import com.stockpulse.advisor.Advice;
import com.stockpulse.advisor.ProductContext;
import com.stockpulse.domain.TriggerReason;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the situation-specific template and fills it with structured product context.
 * Inventory-low and demand-spike are separate documents because they are different merchandising decisions.
 */
@Component
public class PromptFactory {

    public static final String INVENTORY_LOW = "inventory-low";
    public static final String DEMAND_SPIKE = "demand-spike";
    public static final String MANUAL_REVIEW = "manual-review";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final String system;
    private final String systemStream;
    private final Map<String, String> templates = new HashMap<>();

    public PromptFactory() {
        this.system = load("system");
        this.systemStream = system + "\n" + load("system-stream");
        for (String name : new String[]{INVENTORY_LOW, DEMAND_SPIKE, MANUAL_REVIEW}) {
            templates.put(name, load(name));
        }
    }

    public Prompt build(ProductContext ctx, TriggerReason trigger, Advice ruleBaseline, boolean streaming) {
        String template = templateFor(ctx, trigger);
        String user = render(templates.get(template), variables(ctx, trigger, ruleBaseline));
        return new Prompt(template, streaming ? systemStream : system, user);
    }

    static String templateFor(ProductContext ctx, TriggerReason trigger) {
        return switch (trigger) {
            case INVENTORY_LOW -> INVENTORY_LOW;
            case DEMAND_SPIKE -> DEMAND_SPIKE;
            case MANUAL -> MANUAL_REVIEW;
            case INITIAL -> ctx.inventoryLow() ? INVENTORY_LOW : ctx.demandSpike() ? DEMAND_SPIKE : MANUAL_REVIEW;
        };
    }

    private Map<String, String> variables(ProductContext ctx, TriggerReason trigger, Advice baseline) {
        Map<String, String> v = new HashMap<>();
        v.put("triggerDetail", triggerDetail(ctx, trigger));
        v.put("name", ctx.name());
        v.put("sku", ctx.sku());
        v.put("category", ctx.category().name());
        v.put("currentPrice", money(ctx.currentPrice()));
        v.put("costPrice", ctx.costPrice() == null ? "not provided" : money(ctx.costPrice()));
        v.put("marginFloor", ctx.marginFloorPct() == null ? "not set" : ctx.marginFloorPct().stripTrailingZeros().toPlainString() + "%");
        v.put("stockLevel", String.valueOf(ctx.stockLevel()));
        v.put("reorderThreshold", String.valueOf(ctx.reorderThreshold()));
        v.put("shortfall", String.valueOf(Math.max(0, ctx.reorderThreshold() - ctx.stockLevel())));
        v.put("stockPctOfThreshold", ctx.reorderThreshold() == 0 ? "n/a"
                : String.valueOf(Math.round(100.0 * ctx.stockLevel() / ctx.reorderThreshold())));
        v.put("stockStatus", ctx.stockLevel() == 0 ? "OUT OF STOCK"
                : ctx.inventoryLow() ? "BELOW threshold" : "above threshold");
        Double cover = ctx.daysOfCover();
        v.put("daysOfCover", cover == null ? "unknown (no orders in the last 24h)" : fmt1(cover));
        int leadTime = ctx.category().defaultLeadTimeDays();
        v.put("defaultLeadTime", String.valueOf(leadTime));
        v.put("stockoutBeforeLeadTime", cover != null && cover < leadTime
                ? "\n- WARNING: at the current pace stock runs out BEFORE a new order placed today can arrive" : "");
        v.put("outOfStockNote", ctx.stockLevel() == 0
                ? "\n- OUT OF STOCK: there are no units to clear, so CLEARANCE does not apply now. The price you set is the "
                + "price the restocked units will launch at; decide whether to restock at all and size the reorder accordingly."
                : "");
        v.put("velocity", String.valueOf(ctx.demandVelocity()));
        v.put("peerCount", String.valueOf(ctx.peerCount()));
        v.put("categoryAvg", fmt1(ctx.categoryAverageVelocity()));
        v.put("velocityRatio", fmt1(ctx.velocityRatio()));
        v.put("spikeMultiplier", fmt1(ctx.spikeMultiplier()));
        v.put("spikeThreshold", fmt1(ctx.spikeThreshold()));
        v.put("additionalSignals", additionalSignals(ctx));
        v.put("rulePrice", money(baseline.pricing().recommendedPrice()));
        v.put("ruleTactic", baseline.pricing().tactic().name());
        v.put("ruleQuantity", String.valueOf(baseline.reorder().recommendedQuantity()));
        return v;
    }

    private static String triggerDetail(ProductContext ctx, TriggerReason trigger) {
        return switch (trigger) {
            case INVENTORY_LOW -> "a sale or stock update just left stock at " + ctx.stockLevel()
                    + " units, below the reorder threshold of " + ctx.reorderThreshold() + ".";
            case DEMAND_SPIKE -> "the latest order pushed demand velocity to " + ctx.demandVelocity()
                    + " orders/24h, above the spike threshold of " + fmt1(ctx.spikeThreshold()) + ".";
            case INITIAL -> "this product just entered the catalog and is already in this situation.";
            case MANUAL -> "a merchandiser requested a review from the console.";
        };
    }

    private static String additionalSignals(ProductContext ctx) {
        if (ctx.demandSpike()) {
            return "- ADDITIONAL SIGNAL: demand is ALSO spiking (" + fmt1(ctx.velocityRatio())
                    + "x category average, above the " + fmt1(ctx.spikeMultiplier())
                    + "x spike threshold). Scarcity plus strong demand usually argues against CLEARANCE.\n";
        }
        if (ctx.velocityRatio() < 0.7) {
            return "- NOTE: demand is weak versus peers; consider whether this item should be cleared rather than restocked at volume.\n";
        }
        return "";
    }

    static String render(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) {
                throw new IllegalStateException("Prompt placeholder {{" + m.group(1) + "}} has no value");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String load(String name) {
        try {
            return StreamUtils.copyToString(new ClassPathResource("prompts/" + name + ".txt").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt template " + name, e);
        }
    }
}
