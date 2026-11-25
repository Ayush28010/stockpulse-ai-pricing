package com.stockpulse.product;

import com.stockpulse.domain.ProductStatus;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.suggestion.PricingSuggestionRepository;
import org.springframework.stereotype.Component;

/** Re-derives product status from facts after every write that can affect it. */
@Component
public class ProductStatusPolicy {

    private final PricingSuggestionRepository pricing;

    public ProductStatusPolicy(PricingSuggestionRepository pricing) {
        this.pricing = pricing;
    }

    public void refresh(Product product) {
        boolean pendingPricing = pricing.existsByProduct_IdAndStatus(product.getId(), SuggestionStatus.PENDING);
        product.transitionTo(ProductStatus.derive(product.getStockLevel(), pendingPricing));
    }
}
