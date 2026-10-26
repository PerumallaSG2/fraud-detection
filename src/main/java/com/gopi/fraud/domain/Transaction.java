package com.gopi.fraud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String merchant;

    @Column(name = "merchant_category", nullable = false)
    private String merchantCategory;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "transaction_time", nullable = false)
    private OffsetDateTime transactionTime;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RuleHit> ruleHits = new ArrayList<>();

    protected Transaction() {
    }

    public Transaction(String cardId, BigDecimal amount, String merchant, String merchantCategory,
                       String city, double latitude, double longitude, OffsetDateTime transactionTime,
                       int riskScore, Decision decision) {
        this.id = UUID.randomUUID();
        this.cardId = cardId;
        this.amount = amount;
        this.merchant = merchant;
        this.merchantCategory = merchantCategory;
        this.city = city;
        this.latitude = latitude;
        this.longitude = longitude;
        this.transactionTime = transactionTime;
        this.riskScore = riskScore;
        this.decision = decision;
        this.createdAt = Instant.now();
    }

    public void addRuleHit(RuleHit hit) {
        hit.setTransaction(this);
        ruleHits.add(hit);
    }

    public UUID getId() {
        return id;
    }

    public String getCardId() {
        return cardId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public String getCity() {
        return city;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public OffsetDateTime getTransactionTime() {
        return transactionTime;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Decision getDecision() {
        return decision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<RuleHit> getRuleHits() {
        return Collections.unmodifiableList(ruleHits);
    }
}
