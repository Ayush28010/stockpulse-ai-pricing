package com.stockpulse.domain;

public enum GeneratedBy {
    AI,
    RULE,
    /** AI strategy was active but its output (or the call) failed; rule-based result substituted. */
    RULE_FALLBACK
}
