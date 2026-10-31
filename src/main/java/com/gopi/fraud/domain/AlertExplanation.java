package com.gopi.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * The explanation of a flagged transaction's decision. It never changes the decision: it only describes it.
 * New instances are always inserted, never merged, so a second explanation for the same transaction fails on the
 * primary key instead of silently replacing the first.
 */
@Entity
@Table(name = "alert_explanations")
public class AlertExplanation implements Persistable<UUID> {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExplanationSource source;

    /** The model that wrote it; null for {@link ExplanationSource#RULES}. */
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected AlertExplanation() {
    }

    public AlertExplanation(UUID transactionId, String text, ExplanationSource source, String model) {
        this.transactionId = transactionId;
        this.text = text;
        this.source = source;
        this.model = model;
        this.createdAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getText() {
        return text;
    }

    public ExplanationSource getSource() {
        return source;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
