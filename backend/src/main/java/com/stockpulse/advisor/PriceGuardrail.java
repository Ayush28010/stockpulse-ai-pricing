package com.stockpulse.advisor;

/** Post-processing applied to every strategy's pricing output. Sprint 2 guardrails (category caps, cooldowns) plug in here. */
public interface PriceGuardrail {

    PricingAdvice apply(ProductContext context, PricingAdvice advice);
}
