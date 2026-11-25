package com.stockpulse.domain;

/** PENDING ──accept──▶ ACCEPTED, PENDING ──reject/supersede──▶ REJECTED. Terminal states never change. */
public enum SuggestionStatus {
    PENDING,
    ACCEPTED,
    REJECTED;

    public boolean canTransitionTo(SuggestionStatus next) {
        return this == PENDING && (next == ACCEPTED || next == REJECTED);
    }
}
