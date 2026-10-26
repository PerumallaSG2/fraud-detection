package com.gopi.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "transaction_rule_hits")
public class RuleHit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private int points;

    @Column(nullable = false)
    private String reason;

    protected RuleHit() {
    }

    public RuleHit(String ruleName, int points, String reason) {
        this.ruleName = ruleName;
        this.points = points;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public String getRuleName() {
        return ruleName;
    }

    public int getPoints() {
        return points;
    }

    public String getReason() {
        return reason;
    }
}
