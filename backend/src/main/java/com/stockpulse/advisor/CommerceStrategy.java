package com.stockpulse.advisor;

import com.stockpulse.domain.SuggestionType;
import com.stockpulse.domain.TriggerReason;

import java.util.Set;

/**
 * The commerce contract. One call returns pricing and/or reorder advice for a product situation.
 * <p>
 * Adding a strategy (e.g. sprint 2 {@code CompetitorAwareStrategy}) means implementing this interface
 * and annotating it {@code @Component}; {@link StrategyRegistry} discovers it and it becomes selectable
 * via {@code PUT /settings/strategy} with no other code change.
 */
public interface CommerceStrategy {

    /** Stable, upper-case identifier used in config and the API, e.g. "RULE", "AI". */
    String name();

    String description();

    /**
     * @param wanted which halves to produce; halves not in the set must be null in the result.
     *               Implementations may return null for a wanted half; {@link CommerceAdvisor} fills it from rules.
     */
    Advice advise(ProductContext context, TriggerReason trigger, Set<SuggestionType> wanted);
}
