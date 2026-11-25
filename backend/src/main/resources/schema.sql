-- StockPulse schema (SQLite). Owned here rather than by Hibernate so CHECKs and partial indexes are explicit.

CREATE TABLE IF NOT EXISTS products (
    id                TEXT PRIMARY KEY,
    sku               TEXT    NOT NULL UNIQUE,
    name              TEXT    NOT NULL,
    category          TEXT    NOT NULL CHECK (category IN ('ELECTRONICS', 'APPAREL', 'HOME')),
    current_price     NUMERIC NOT NULL CHECK (current_price > 0),
    stock_level       INTEGER NOT NULL CHECK (stock_level >= 0),
    reorder_threshold INTEGER NOT NULL CHECK (reorder_threshold >= 0),
    demand_velocity   INTEGER NOT NULL DEFAULT 0 CHECK (demand_velocity >= 0),
    status            TEXT    NOT NULL CHECK (status IN ('ACTIVE', 'PRICE_REVIEW_PENDING', 'OUT_OF_STOCK')),
    -- Sprint 2 extension points (nullable today)
    cost_price        NUMERIC CHECK (cost_price IS NULL OR cost_price > 0),
    margin_floor_pct  NUMERIC CHECK (margin_floor_pct IS NULL OR (margin_floor_pct >= 0 AND margin_floor_pct <= 100)),
    supplier_id       TEXT,
    version           INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sale_orders (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id TEXT    NOT NULL REFERENCES products (id),
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_sale_orders_product_time ON sale_orders (product_id, created_at);

CREATE TABLE IF NOT EXISTS pricing_suggestions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id        TEXT    NOT NULL REFERENCES products (id),
    current_price     NUMERIC NOT NULL,
    recommended_price NUMERIC NOT NULL CHECK (recommended_price > 0),
    direction         TEXT    NOT NULL CHECK (direction IN ('INCREASE', 'DECREASE', 'HOLD')),
    tactic            TEXT    NOT NULL CHECK (tactic IN ('PROTECT_INVENTORY', 'CLEARANCE', 'CAPITALIZE_ON_DEMAND', 'HOLD')),
    confidence        REAL    NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    reasoning         TEXT    NOT NULL,
    status            TEXT    NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    trigger_reason    TEXT    NOT NULL CHECK (trigger_reason IN ('INITIAL', 'INVENTORY_LOW', 'DEMAND_SPIKE', 'MANUAL')),
    generated_by      TEXT    NOT NULL CHECK (generated_by IN ('AI', 'RULE', 'RULE_FALLBACK')),
    strategy_name     TEXT    NOT NULL,
    fallback_reason   TEXT,
    flagged           INTEGER NOT NULL DEFAULT 0,
    flag_note         TEXT,
    decision_note     TEXT,
    created_at        INTEGER NOT NULL,
    decided_at        INTEGER,
    version           INTEGER NOT NULL DEFAULT 0
);
-- Idempotency: at most one PENDING pricing suggestion per product + trigger.
CREATE UNIQUE INDEX IF NOT EXISTS ux_pricing_pending_per_trigger
    ON pricing_suggestions (product_id, trigger_reason) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_pricing_status ON pricing_suggestions (status);

CREATE TABLE IF NOT EXISTS reorder_suggestions (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id           TEXT    NOT NULL REFERENCES products (id),
    current_stock        INTEGER NOT NULL,
    recommended_quantity INTEGER NOT NULL CHECK (recommended_quantity > 0),
    lead_time_days       INTEGER NOT NULL CHECK (lead_time_days > 0),
    confidence           REAL    NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    reasoning            TEXT    NOT NULL,
    status               TEXT    NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    trigger_reason       TEXT    NOT NULL CHECK (trigger_reason IN ('INITIAL', 'INVENTORY_LOW', 'DEMAND_SPIKE', 'MANUAL')),
    generated_by         TEXT    NOT NULL CHECK (generated_by IN ('AI', 'RULE', 'RULE_FALLBACK')),
    strategy_name        TEXT    NOT NULL,
    fallback_reason      TEXT,
    supplier_id          TEXT,
    decision_note        TEXT,
    created_at           INTEGER NOT NULL,
    decided_at           INTEGER,
    version              INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_reorder_pending_per_trigger
    ON reorder_suggestions (product_id, trigger_reason) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_reorder_status ON reorder_suggestions (status);

CREATE TABLE IF NOT EXISTS price_history (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id            TEXT    NOT NULL REFERENCES products (id),
    old_price             NUMERIC NOT NULL,
    new_price             NUMERIC NOT NULL,
    pricing_suggestion_id INTEGER REFERENCES pricing_suggestions (id),
    trigger_reason        TEXT,
    changed_at            INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_price_history_product ON price_history (product_id, changed_at);

CREATE TABLE IF NOT EXISTS app_settings (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT    NOT NULL,
    updated_at    INTEGER NOT NULL
);
