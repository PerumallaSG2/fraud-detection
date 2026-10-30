package com.gopi.fraud.dto;

import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID transactionId,
        String cardId,
        int riskScore,
        Decision decision,
        AlertStatus status,
        String note,
        Instant createdAt,
        Instant resolvedAt) {
}
