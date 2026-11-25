package com.stockpulse.advisor;

import com.stockpulse.common.ApiException;
import com.stockpulse.settings.AppSetting;
import com.stockpulse.settings.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects every {@link CommerceStrategy} bean by name and resolves the active one on each call,
 * so a switch takes effect for the next HTTP request or async trigger without a restart.
 * The active name is runtime config in {@code app_settings}: changed via PUT /settings/strategy or edited
 * directly in the table (re-read every {@code stockpulse.strategy.refresh-interval}).
 */
@Component
public class StrategyRegistry {

    static final String SETTING_KEY = "commerce.strategy";
    private static final Logger log = LoggerFactory.getLogger(StrategyRegistry.class);

    private final Map<String, CommerceStrategy> strategies = new LinkedHashMap<>();
    private final AppSettingRepository settings;
    private final String defaultName;
    private final AtomicReference<String> active = new AtomicReference<>();
    private final Duration refreshInterval;
    private volatile long loadedAtNanos;

    public StrategyRegistry(List<CommerceStrategy> beans, AppSettingRepository settings,
                            @Value("${stockpulse.strategy.default-name:AI}") String defaultName,
                            @Value("${stockpulse.strategy.refresh-interval:PT5S}") Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
        for (CommerceStrategy strategy : beans) {
            String key = strategy.name().toUpperCase(Locale.ROOT);
            if (strategies.putIfAbsent(key, strategy) != null) {
                throw new IllegalStateException("Duplicate commerce strategy name: " + key);
            }
        }
        if (!strategies.containsKey(RuleBasedStrategy.NAME)) {
            throw new IllegalStateException("RuleBasedStrategy must be registered as the fallback");
        }
        String normalized = defaultName.toUpperCase(Locale.ROOT);
        this.defaultName = strategies.containsKey(normalized) ? normalized : RuleBasedStrategy.NAME;
        this.settings = settings;
        log.info("Commerce strategies registered: {} (default {})", strategies.keySet(), this.defaultName);
    }

    public CommerceStrategy active() {
        return strategies.get(activeName());
    }

    public String activeName() {
        String current = active.get();
        if (current == null || System.nanoTime() - loadedAtNanos >= refreshInterval.toNanos()) {
            String persisted = settings.findById(SETTING_KEY)
                    .map(s -> s.getValue().trim().toUpperCase(Locale.ROOT))
                    .filter(strategies::containsKey)
                    .orElse(current != null ? current : defaultName);
            String previous = active.getAndSet(persisted);
            loadedAtNanos = System.nanoTime();
            if (previous != null && !previous.equals(persisted)) {
                log.info("Commerce strategy changed in config {} -> {}", previous, persisted);
            }
            current = persisted;
        }
        return current;
    }

    @Transactional
    public CommerceStrategy switchTo(String name) {
        String key = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        CommerceStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw ApiException.badRequest("UNKNOWN_STRATEGY",
                    "Unknown strategy '" + name + "'. Available: " + strategies.keySet());
        }
        settings.save(new AppSetting(SETTING_KEY, key));
        String previous = active.getAndSet(key);
        loadedAtNanos = System.nanoTime();
        log.info("Commerce strategy switched {} -> {}", previous, key);
        return strategy;
    }

    public Collection<CommerceStrategy> available() {
        return strategies.values();
    }
}
