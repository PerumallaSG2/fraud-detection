package com.gopi.fraud.service;

import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    /** Builds the entity with one rule hit per triggered rule. */
    public Transaction toEntity(TransactionRequest request, ScoringResult scoring) {
        Transaction transaction = new Transaction(request.cardId(), request.amount(), request.merchant(),
                request.merchantCategory(), request.city(), request.latitude(), request.longitude(),
                request.timestamp(), scoring.score(), scoring.decision());
        scoring.triggeredRules().forEach(result ->
                transaction.addRuleHit(new RuleHit(result.ruleName(), result.points(), result.reason())));
        return transaction;
    }

    public TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getCardId(), transaction.getAmount(),
                transaction.getMerchant(), transaction.getMerchantCategory(), transaction.getCity(),
                transaction.getLatitude(), transaction.getLongitude(), transaction.getTransactionTime(),
                transaction.getRiskScore(), transaction.getDecision(),
                transaction.getRuleHits().stream()
                        .map(hit -> new TriggeredRuleDto(hit.getRuleName(), hit.getPoints(), hit.getReason()))
                        .toList(),
                transaction.getCreatedAt());
    }
}
