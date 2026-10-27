package com.gopi.fraud.rules;

import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The incoming transaction, as seen by the rules.
 *
 * <p>{@code recentHistory} lazily looks up the card's most recent non-BLOCK transactions, newest first, given a
 * maximum count. It is only invoked by rules that need history, so the database is not queried otherwise.
 *
 * <p>{@code recentTransactionCount} records this transaction and returns how many of the card's transactions fall in
 * the given window ending at its timestamp, including it; empty if the count is unavailable. Calling it records the
 * transaction, so it is only invoked by rules that need it, and a rule should call it at most once.
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
        IntFunction<List<Transaction>> recentHistory,
        Function<Duration, OptionalInt> recentTransactionCount) {
}
