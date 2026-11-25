package com.stockpulse.agent;

import java.time.Instant;

/** Published inside the write transaction; handled only after commit so the loop sees committed state. */
public record InventoryChangedEvent(String productId, ChangeSource source, Instant occurredAt) {

    public InventoryChangedEvent(String productId, ChangeSource source) {
        this(productId, source, Instant.now());
    }
}
