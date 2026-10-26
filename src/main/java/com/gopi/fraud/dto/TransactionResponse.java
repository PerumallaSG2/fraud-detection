package com.gopi.fraud.dto;

import com.gopi.fraud.domain.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String cardId,
        BigDecimal amount,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp,
        int riskScore,
        Decision decision,
        List<TriggeredRuleDto> triggeredRules,
        Instant createdAt) {
}
