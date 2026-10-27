package com.gopi.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.config.FraudProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VelocityRuleTest {

    private final FraudProperties properties = new FraudProperties();
    private final VelocityRule rule = new VelocityRule(properties);

    @Test
    void doesNotFireAtTheLimit() {
        assertThat(rule.evaluate(contextWithCount(5)).triggered()).isFalse();
    }

    @Test
    void firesAboveTheLimit() {
        RuleResult result = rule.evaluate(contextWithCount(6));

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("VELOCITY");
        assertThat(result.points()).isEqualTo(30);
        assertThat(result.reason()).isEqualTo("6 transactions within 60s exceeds the limit of 5");
    }

    @Test
    void doesNotFireForAFirstTransaction() {
        assertThat(rule.evaluate(contextWithCount(1)).triggered()).isFalse();
    }

    @Test
    void doesNotFireWhenTheCountIsUnavailable() {
        RuleResult result = rule.evaluate(contextWith(window -> OptionalInt.empty()));

        assertThat(result.triggered()).isFalse();
        assertThat(result.points()).isZero();
    }

    @Test
    void asksForTheCountExactlyOnceWithTheConfiguredWindow() {
        properties.getVelocity().setWindow(Duration.ofSeconds(90));
        List<Duration> windows = new ArrayList<>();

        rule.evaluate(contextWith(window -> {
            windows.add(window);
            return OptionalInt.of(6);
        }));

        // Each call records the transaction, so a second call would double-count it.
        assertThat(windows).containsExactly(Duration.ofSeconds(90));
    }

    @Test
    void asksForTheCountExactlyOnceWhenItDoesNotFire() {
        List<Duration> windows = new ArrayList<>();

        rule.evaluate(contextWith(window -> {
            windows.add(window);
            return OptionalInt.of(1);
        }));

        assertThat(windows).hasSize(1);
    }

    @Test
    void respectsConfiguredPointsAndLimit() {
        properties.getVelocity().setPoints(45);
        properties.getVelocity().setMaxTransactions(2);

        assertThat(rule.evaluate(contextWithCount(2)).triggered()).isFalse();
        RuleResult result = rule.evaluate(contextWithCount(3));
        assertThat(result.triggered()).isTrue();
        assertThat(result.points()).isEqualTo(45);
        assertThat(result.reason()).isEqualTo("3 transactions within 60s exceeds the limit of 2");
    }

    @Test
    void doesNotLookUpCardHistory() {
        // contextWith's history function throws if called.
        rule.evaluate(contextWithCount(6));
    }

    private static RuleContext contextWithCount(int count) {
        return contextWith(window -> OptionalInt.of(count));
    }

    private static RuleContext contextWith(Function<Duration, OptionalInt> recentTransactionCount) {
        return new RuleContext("card-1", new BigDecimal("50.00"), "Shop", "GROCERY", "Austin",
                30.27, -97.74, OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC),
                limit -> {
                    throw new AssertionError("this rule must not look up card history");
                },
                recentTransactionCount);
    }
}
