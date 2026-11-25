package com.stockpulse.agent;

import com.stockpulse.advisor.ProductContext;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.ProductContextLoader;
import com.stockpulse.recommendation.RecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * The agentic loop: observe (committed inventory change) -> reason (active strategy) -> act (queue PENDING
 * suggestions) -> checkpoint (merchandiser accepts/rejects). Runs off the request thread after commit.
 */
@Component
public class RecommendationTriggerHandler {

    private static final Logger log = LoggerFactory.getLogger(RecommendationTriggerHandler.class);

    private final ProductContextLoader contexts;
    private final TriggerEvaluator evaluator;
    private final RecommendationService recommendations;

    public RecommendationTriggerHandler(ProductContextLoader contexts, TriggerEvaluator evaluator,
                                        RecommendationService recommendations) {
        this.contexts = contexts;
        this.evaluator = evaluator;
        this.recommendations = recommendations;
    }

    @Async("recommendationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInventoryChanged(InventoryChangedEvent event) {
        try {
            ProductContext ctx = contexts.load(event.productId());
            List<TriggerReason> fired = evaluator.evaluate(ctx, event.source());
            if (fired.isEmpty()) {
                log.debug("{} {}: no trigger (stock {}/{}, velocity {} vs spike {})", event.productId(), event.source(),
                        ctx.stockLevel(), ctx.reorderThreshold(), ctx.demandVelocity(), ctx.spikeThreshold());
                return;
            }
            log.info("{} {}: triggers fired {}", event.productId(), event.source(), fired);
            for (TriggerReason trigger : fired) {
                recommendations.generateForTrigger(event.productId(), trigger);
            }
        } catch (RuntimeException e) {
            log.error("Agentic loop failed for {} ({}); next inventory change will retry", event.productId(),
                    event.source(), e);
        }
    }
}
