package com.gopi.fraud.dto;

import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import java.time.Instant;
import java.util.UUID;

/** {@code explanation} is null until one is written, and for alerts whose decision was never explained. */
public record AlertResponse(
        UUID id,
        UUID transactionId,
        String cardId,
        int riskScore,
        Decision decision,
        AlertStatus status,
        String note,
        Instant createdAt,
        Instant resolvedAt,
        AlertExplanationResponse explanation) {
}
