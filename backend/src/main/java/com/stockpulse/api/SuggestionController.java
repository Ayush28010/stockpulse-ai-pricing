package com.stockpulse.api;

import com.stockpulse.api.dto.DecisionRequest;
import com.stockpulse.api.dto.DecisionResponse;
import com.stockpulse.api.dto.PricingSuggestionResponse;
import com.stockpulse.api.dto.ReorderSuggestionResponse;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.suggestion.SuggestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SuggestionController {

    private final SuggestionService suggestions;

    public SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @GetMapping("/pricing-suggestions")
    public List<PricingSuggestionResponse> listPricing(@RequestParam(required = false) SuggestionStatus status,
                                                       @RequestParam(required = false) String productId,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return suggestions.listPricing(status, productId, limit);
    }

    /** Accept updates Product.currentPrice in the same transaction; reject leaves the price untouched. */
    @PatchMapping("/pricing-suggestions/{id}")
    public DecisionResponse<PricingSuggestionResponse> decidePricing(@PathVariable Long id,
                                                                     @Valid @RequestBody DecisionRequest request) {
        return suggestions.decidePricing(id, request.status(), request.note());
    }

    @GetMapping("/reorder-suggestions")
    public List<ReorderSuggestionResponse> listReorder(@RequestParam(required = false) SuggestionStatus status,
                                                       @RequestParam(required = false) String productId,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return suggestions.listReorder(status, productId, limit);
    }

    /** Accept increments stock (simulated inbound shipment) in the same transaction. */
    @PatchMapping("/reorder-suggestions/{id}")
    public DecisionResponse<ReorderSuggestionResponse> decideReorder(@PathVariable Long id,
                                                                     @Valid @RequestBody DecisionRequest request) {
        return suggestions.decideReorder(id, request.status(), request.note());
    }
}
