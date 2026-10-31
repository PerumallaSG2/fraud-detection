package com.gopi.fraud.service;

import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import com.gopi.fraud.messaging.TransactionDecided;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    /** Builds the entity with the given id and one rule hit per triggered rule. */
    public Transaction toEntity(UUID id, TransactionRequest request, ScoringResult scoring) {
        Transaction transaction = new Transaction(id, request.cardId(), request.amount(), request.merchant(),
                request.merchantCategory(), request.city(), request.latitude(), request.longitude(),
                request.timestamp(), scoring.score(), scoring.decision());
        scoring.triggeredRules().forEach(result ->
                transaction.addRuleHit(new RuleHit(result.ruleName(), result.points(), result.reason())));
        return transaction;
    }

    public TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getCardId(), twoDecimals(transaction.getAmount()),
                transaction.getMerchant(), transaction.getMerchantCategory(), transaction.getCity(),
                transaction.getLatitude(), transaction.getLongitude(), transaction.getTransactionTime(),
                transaction.getRiskScore(), transaction.getDecision(),
                transaction.getRuleHits().stream()
                        .map(hit -> new TriggeredRuleDto(hit.getRuleName(), hit.getPoints(), hit.getReason()))
                        .toList(),
                transaction.getCreatedAt());
    }

    public TransactionDecided toDecided(Transaction transaction) {
        return new TransactionDecided(transaction.getId(), transaction.getCardId(), transaction.getRiskScore(),
                transaction.getDecision());
    }

    /**
     * Entities loaded from the database skip the Transaction constructor, so their amount keeps whatever scale
     * the driver returned. Normalize here so every response shows 2 decimals, whatever its source.
     */
    private static BigDecimal twoDecimals(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
