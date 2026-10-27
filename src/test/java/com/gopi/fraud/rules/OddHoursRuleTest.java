package com.gopi.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.config.FraudProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class OddHoursRuleTest {

    private final OddHoursRule rule = new OddHoursRule(new FraudProperties());

    @Test
    void firesJustBeforeWindowEnd() {
        RuleResult result = rule.evaluate(contextAt(at(4, 59, ZoneOffset.UTC)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("ODD_HOURS");
        assertThat(result.points()).isEqualTo(20);
        assertThat(result.reason()).contains("04:59");
    }

    @Test
    void doesNotFireAtWindowEnd() {
        RuleResult result = rule.evaluate(contextAt(at(5, 0, ZoneOffset.UTC)));

        assertThat(result.triggered()).isFalse();
        assertThat(result.points()).isZero();
    }

    @Test
    void firesAtMidnight() {
        assertThat(rule.evaluate(contextAt(at(0, 0, ZoneOffset.UTC))).triggered()).isTrue();
    }

    @Test
    void doesNotFireJustBeforeMidnight() {
        assertThat(rule.evaluate(contextAt(at(23, 59, ZoneOffset.UTC))).triggered()).isFalse();
    }

    @Test
    void usesTheTimestampsOwnOffset() {
        // 03:00 at -05:00 is 08:00 UTC, yet still odd hours in the timestamp's own offset.
        assertThat(rule.evaluate(contextAt(at(3, 0, ZoneOffset.ofHours(-5)))).triggered()).isTrue();

        // Same instant as 03:00 UTC, but 19:00 the previous day at -08:00: not odd hours locally.
        OffsetDateTime sameInstant = at(3, 0, ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(-8));
        assertThat(sameInstant.getHour()).isEqualTo(19);
        assertThat(rule.evaluate(contextAt(sameInstant)).triggered()).isFalse();
    }

    @Test
    void supportsWindowWrappingPastMidnight() {
        FraudProperties properties = new FraudProperties();
        properties.getOddHours().setStartHour(23);
        properties.getOddHours().setEndHour(5);
        OddHoursRule wrapping = new OddHoursRule(properties);

        assertThat(wrapping.evaluate(contextAt(at(23, 0, ZoneOffset.UTC))).triggered()).isTrue();
        assertThat(wrapping.evaluate(contextAt(at(4, 59, ZoneOffset.UTC))).triggered()).isTrue();
        assertThat(wrapping.evaluate(contextAt(at(22, 59, ZoneOffset.UTC))).triggered()).isFalse();
        assertThat(wrapping.evaluate(contextAt(at(5, 0, ZoneOffset.UTC))).triggered()).isFalse();
    }

    @Test
    void usesConfiguredPoints() {
        FraudProperties properties = new FraudProperties();
        properties.getOddHours().setPoints(35);

        RuleResult result = new OddHoursRule(properties).evaluate(contextAt(at(2, 0, ZoneOffset.UTC)));

        assertThat(result.points()).isEqualTo(35);
    }

    private static OffsetDateTime at(int hour, int minute, ZoneOffset offset) {
        return OffsetDateTime.of(2026, 10, 25, hour, minute, 0, 0, offset);
    }

    private static RuleContext contextAt(OffsetDateTime timestamp) {
        return new RuleContext("card-1", new BigDecimal("50.00"), "Shop", "GROCERY", "Austin",
                30.27, -97.74, timestamp,
                limit -> {
                    throw new AssertionError("this rule must not look up card history");
                },
                window -> OptionalInt.of(1));
    }
}
