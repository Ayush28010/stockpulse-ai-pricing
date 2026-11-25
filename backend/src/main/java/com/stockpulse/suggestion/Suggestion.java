package com.stockpulse.suggestion;

import com.stockpulse.common.ApiException;
import com.stockpulse.common.InstantMillisConverter;
import com.stockpulse.domain.GeneratedBy;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;

/** Shared state machine for pricing and reorder suggestions: PENDING -> ACCEPTED | REJECTED. */
@MappedSuperclass
public abstract class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerReason triggerReason;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GeneratedBy generatedBy;

    @Column(nullable = false)
    private String strategyName;

    private String fallbackReason;

    private String decisionNote;

    @Convert(converter = InstantMillisConverter.class)
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Convert(converter = InstantMillisConverter.class)
    private Instant decidedAt;

    @Version
    private Long version;

    protected Suggestion() {
    }

    protected Suggestion(Product product, TriggerReason triggerReason, double confidence, String reasoning,
                         GeneratedBy generatedBy, String strategyName, String fallbackReason) {
        this.product = product;
        this.triggerReason = triggerReason;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.generatedBy = generatedBy;
        this.strategyName = strategyName;
        this.fallbackReason = fallbackReason;
        this.createdAt = Instant.now();
    }

    public void assertPending() {
        if (status != SuggestionStatus.PENDING) {
            throw ApiException.invalidTransition(getClass().getSimpleName(), id, status, SuggestionStatus.ACCEPTED);
        }
    }

    public void accept(String note) {
        transition(SuggestionStatus.ACCEPTED, note);
    }

    public void reject(String note) {
        transition(SuggestionStatus.REJECTED, note);
    }

    private void transition(SuggestionStatus next, String note) {
        if (!status.canTransitionTo(next)) {
            throw ApiException.invalidTransition(getClass().getSimpleName(), id, status, next);
        }
        status = next;
        decisionNote = note;
        decidedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public SuggestionStatus getStatus() { return status; }
    public TriggerReason getTriggerReason() { return triggerReason; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public GeneratedBy getGeneratedBy() { return generatedBy; }
    public String getStrategyName() { return strategyName; }
    public String getFallbackReason() { return fallbackReason; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Long getVersion() { return version; }
}
