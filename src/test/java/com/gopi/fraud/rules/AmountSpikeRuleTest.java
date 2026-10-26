package com.gopi.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AmountSpikeRuleTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    private final AmountSpikeRule rule = new AmountSpikeRule(new FraudProperties());

    @Test
    void firesWhenAmountExceedsMultipleOfAverage() {
        List<Transaction> history = repeated(5, "100.00");   // average 100 -> threshold 300

        RuleResult result = rule.evaluate(contextFor("300.01", history));

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("AMOUNT_SPIKE");
        assertThat(result.points()).isEqualTo(40);
        assertThat(result.reason()).contains("300.01");
    }

    @Test
    void doesNotFireAtExactlyTheMultiple() {
        RuleResult result = rule.evaluate(contextFor("300.00", repeated(5, "100.00")));

        assertThat(result.triggered()).isFalse();
        assertThat(result.points()).isZero();
    }

    @Test
    void usesAverageOfMixedAmounts() {
        List<Transaction> history = List.of(tx("10.00"), tx("20.00"), tx("30.00"), tx("40.00"), tx("50.00")); // avg 30

        assertThat(rule.evaluate(contextFor("90.00", history)).triggered()).isFalse();
        assertThat(rule.evaluate(contextFor("90.01", history)).triggered()).isTrue();
    }

    @Test
    void usesFallbackLimitWhenHistoryTooShort() {
        List<Transaction> history = repeated(4, "10.00");    // below minHistory of 5, so the average is ignored

        assertThat(rule.evaluate(contextFor("5000.00", history)).triggered()).isFalse();

        RuleResult over = rule.evaluate(contextFor("5000.01", history));
        assertThat(over.triggered()).isTrue();
        assertThat(over.reason()).contains("5000.01");
    }

    @Test
    void usesFallbackLimitWithNoHistory() {
        assertThat(rule.evaluate(contextFor("100.00", List.of())).triggered()).isFalse();
        assertThat(rule.evaluate(contextFor("6000.00", List.of())).triggered()).isTrue();
    }

    @Test
    void requestsConfiguredLookbackOnlyWhenEvaluated() {
        AtomicInteger requestedLimit = new AtomicInteger();
        RuleContext ctx = new RuleContext("card-1", new BigDecimal("50.00"), "Shop", "GROCERY", "Austin",
                30.27, -97.74, NOW, limit -> {
                    requestedLimit.set(limit);
                    return repeated(5, "100.00");
                });
        assertThat(requestedLimit).hasValue(0);

        rule.evaluate(ctx);

        assertThat(requestedLimit).hasValue(20);
    }

    @Test
    void usesConfiguredSettings() {
        FraudProperties properties = new FraudProperties();
        properties.getAmountSpike().setPoints(55);
        properties.getAmountSpike().setMultiplier(new BigDecimal("2.5"));
        properties.getAmountSpike().setMinHistory(2);
        AmountSpikeRule custom = new AmountSpikeRule(properties);
        List<Transaction> history = repeated(2, "100.00");

        assertThat(custom.evaluate(contextFor("250.00", history)).triggered()).isFalse();
        RuleResult result = custom.evaluate(contextFor("250.01", history));
        assertThat(result.triggered()).isTrue();
        assertThat(result.points()).isEqualTo(55);
    }

    private static List<Transaction> repeated(int count, String amount) {
        return Collections.nCopies(count, tx(amount));
    }

    private static Transaction tx(String amount) {
        return new Transaction("card-1", new BigDecimal(amount), "Shop", "GROCERY", "Austin",
                30.27, -97.74, NOW.minusDays(1), 0, Decision.APPROVE);
    }

    private static RuleContext contextFor(String amount, List<Transaction> history) {
        return new RuleContext("card-1", new BigDecimal(amount), "Shop", "GROCERY", "Austin",
                30.27, -97.74, NOW, limit -> history);
    }
}
