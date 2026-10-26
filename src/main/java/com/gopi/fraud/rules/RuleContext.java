package com.gopi.fraud.rules;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The incoming transaction, as seen by the rules. */
public record RuleContext(
        String cardId,
        BigDecimal amount,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp) {
}
