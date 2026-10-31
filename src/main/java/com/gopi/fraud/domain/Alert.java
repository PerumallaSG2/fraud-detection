package com.gopi.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An analyst work item for a REVIEW or BLOCK transaction. Starts OPEN; resolved once, as FRAUD or FALSE_POSITIVE. */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Alert() {
    }

    /** An OPEN alert for the transaction, with the fields an analyst needs to triage it copied from its decision. */
    public Alert(UUID transactionId, String cardId, int riskScore, Decision decision) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.cardId = cardId;
        this.riskScore = riskScore;
        this.decision = decision;
        this.status = AlertStatus.OPEN;
        this.createdAt = Instant.now();
    }

    /** The caller checks the alert is OPEN first (see {@code AlertService.resolve}). */
    public void resolve(AlertStatus outcome, String note) {
        this.status = outcome;
        this.note = note;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getCardId() {
        return cardId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Decision getDecision() {
        return decision;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
