package com.stockpulse.api;

import com.stockpulse.advisor.CommerceStrategy;
import com.stockpulse.advisor.RuleBasedStrategy;
import com.stockpulse.advisor.StrategyRegistry;
import com.stockpulse.ai.LlmClient;
import com.stockpulse.api.dto.StrategyRequest;
import com.stockpulse.api.dto.StrategyResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime strategy switch: persisted, applies to the next HTTP or async call, no restart. */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final StrategyRegistry registry;
    private final LlmClient llm;

    public SettingsController(StrategyRegistry registry, LlmClient llm) {
        this.registry = registry;
        this.llm = llm;
    }

    @GetMapping("/strategy")
    public StrategyResponse strategy() {
        return response();
    }

    @PutMapping("/strategy")
    public StrategyResponse switchStrategy(@Valid @RequestBody StrategyRequest request) {
        registry.switchTo(request.strategy());
        return response();
    }

    private StrategyResponse response() {
        return new StrategyResponse(
                registry.activeName(),
                RuleBasedStrategy.NAME,
                registry.available().stream()
                        .map((CommerceStrategy s) -> new StrategyResponse.StrategyInfo(s.name(), s.description()))
                        .toList(),
                llm.isConfigured(),
                llm.model());
    }
}
