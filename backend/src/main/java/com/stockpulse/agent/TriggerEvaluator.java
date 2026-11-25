package com.stockpulse.agent;

import com.stockpulse.advisor.ProductContext;
import com.stockpulse.domain.TriggerReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Observe step: decides which triggers a committed inventory change fires. */
@Component
public class TriggerEvaluator {

    public List<TriggerReason> evaluate(ProductContext ctx, ChangeSource source) {
        boolean low = ctx.inventoryLow();
        // Velocity only moves on orders, so only an order can push a product into a spike.
        boolean spike = source == ChangeSource.ORDER_PLACED && ctx.demandSpike();
        if (source.isInitial()) {
            return low || ctx.demandSpike() ? List.of(TriggerReason.INITIAL) : List.of();
        }
        List<TriggerReason> fired = new ArrayList<>(2);
        if (low) {
            fired.add(TriggerReason.INVENTORY_LOW);
        }
        if (spike) {
            fired.add(TriggerReason.DEMAND_SPIKE);
        }
        return fired;
    }
}
