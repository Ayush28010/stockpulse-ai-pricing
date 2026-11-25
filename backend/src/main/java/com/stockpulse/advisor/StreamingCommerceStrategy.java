package com.stockpulse.advisor;

import com.stockpulse.domain.TriggerReason;

import java.util.function.Consumer;

/** Optional capability: stream human-readable reasoning tokens before the pricing advice is final (SSE). */
public interface StreamingCommerceStrategy extends CommerceStrategy {

    PricingAdvice streamPricing(ProductContext context, TriggerReason trigger, Consumer<String> onReasoningToken);
}
