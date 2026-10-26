package com.gopi.fraud.rules;

import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The incoming transaction, as seen by the rules.
 *
 * <p>{@code recentHistory} lazily looks up the card's most recent non-BLOCK transactions, newest first, given a
 * maximum count. It is only invoked by rules that need history, so the database is not queried otherwise.
 */
public record RuleContext(
        String cardId,
        BigDecimal amount,
        String merchant,
        String merchantCategory,
        String city,
        double latitude,
        double longitude,
        OffsetDateTime timestamp,
        IntFunction<List<Transaction>> recentHistory) {
}
