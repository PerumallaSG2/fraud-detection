package com.gopi.fraud.messaging;

import com.gopi.fraud.domain.Decision;
import java.util.UUID;

/** A scored and saved transaction's outcome. Published after the transaction is committed, at least once. */
public record TransactionDecided(UUID transactionId, String cardId, int riskScore, Decision decision) {
}
