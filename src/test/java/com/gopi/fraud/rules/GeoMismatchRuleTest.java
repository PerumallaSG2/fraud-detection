package com.gopi.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GeoMismatchRuleTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    // Austin to Lisbon is about 7,853 km; Austin to San Marcos about 47 km; 0.54 degrees north of Austin about 60 km.
    private static final double[] AUSTIN = {30.27, -97.74};
    private static final double[] LISBON = {38.72, -9.14};
    private static final double[] SAN_MARCOS = {29.88, -97.94};
    private static final double[] NORTH_60KM = {30.81, -97.74};

    private final GeoMismatchRule rule = new GeoMismatchRule(new FraudProperties());

    @Test
    void doesNotFireWithoutHistory() {
        RuleResult result = rule.evaluate(contextAt("Lisbon", LISBON, NOW, List.of()));

        assertThat(result.triggered()).isFalse();
        assertThat(result.points()).isZero();
    }

    @Test
    void firesWhenTheImpliedSpeedIsImpossible() {
        Transaction previous = tx("Austin", AUSTIN, NOW.minusMinutes(70));

        RuleResult result = rule.evaluate(contextAt("Lisbon", LISBON, NOW, List.of(previous)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("GEO_MISMATCH");
        assertThat(result.points()).isEqualTo(40);
        assertThat(result.reason()).isEqualTo(
                "Travelled 7,853 km from Austin to Lisbon in 1h 10m (6,731 km/h), above the 900 km/h limit");
    }

    @Test
    void doesNotFireWhenThereWasTimeToTravel() {
        Transaction previous = tx("Austin", AUSTIN, NOW.minusHours(12));   // about 654 km/h

        assertThat(rule.evaluate(contextAt("Lisbon", LISBON, NOW, List.of(previous))).triggered()).isFalse();
    }

    @Test
    void ignoresDistancesUnderTheMinimum() {
        Transaction previous = tx("Austin", AUSTIN, NOW.minusMinutes(1));   // 47 km in a minute, but under 50 km

        assertThat(rule.evaluate(contextAt("San Marcos", SAN_MARCOS, NOW, List.of(previous))).triggered()).isFalse();
    }

    @Test
    void firesForSameInstantAtDifferentPlaces() {
        Transaction previous = tx("Austin", AUSTIN, NOW);

        RuleResult result = rule.evaluate(contextAt("Georgetown", NORTH_60KM, NOW, List.of(previous)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).isEqualTo(
                "Travelled 60 km from Austin to Georgetown at the same instant; the limit is 900 km/h");
    }

    @Test
    void usesTheAbsoluteTimeDifferenceWhenThePreviousTransactionIsLater() {
        Transaction previous = tx("Austin", AUSTIN, NOW.plusMinutes(70));   // arrived first, but timestamped later

        RuleResult result = rule.evaluate(contextAt("Lisbon", LISBON, NOW, List.of(previous)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).contains("in 1h 10m");
    }

    @Test
    void comparesTimestampsAsInstantsAcrossOffsets() {
        // 12:00Z and 07:00-05:00 are the same instant, so this is not 5 hours apart.
        Transaction previous = tx("Austin", AUSTIN, OffsetDateTime.parse("2026-10-25T07:00:00-05:00"));

        RuleResult result = rule.evaluate(contextAt("Lisbon", LISBON, NOW, List.of(previous)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.reason()).contains("at the same instant");
    }

    @Test
    void doesNotFireAtExactlyTheSpeedLimit() {
        FraudProperties properties = new FraudProperties();
        properties.getGeoMismatch().setMaxSpeedKmh(
                GeoMismatchRule.distanceKm(AUSTIN[0], AUSTIN[1], LISBON[0], LISBON[1]));   // covered in exactly 1h
        GeoMismatchRule atLimit = new GeoMismatchRule(properties);
        Transaction previous = tx("Austin", AUSTIN, NOW.minusHours(1));

        assertThat(atLimit.evaluate(contextAt("Lisbon", LISBON, NOW, List.of(previous))).triggered()).isFalse();
        assertThat(atLimit.evaluate(contextAt("Lisbon", LISBON, NOW.minusSeconds(1), List.of(previous))).triggered())
                .isTrue();
    }

    @Test
    void asksForOnlyTheLatestTransaction() {
        AtomicInteger requestedLimit = new AtomicInteger();
        RuleContext ctx = new RuleContext("card-1", new BigDecimal("10.00"), "Shop", "GROCERY", "Lisbon",
                LISBON[0], LISBON[1], NOW, limit -> {
                    requestedLimit.set(limit);
                    return List.of();
                }, window -> OptionalInt.of(1));

        rule.evaluate(ctx);

        assertThat(requestedLimit).hasValue(1);
    }

    @Test
    void usesConfiguredSettings() {
        FraudProperties properties = new FraudProperties();
        properties.getGeoMismatch().setPoints(55);
        properties.getGeoMismatch().setMaxSpeedKmh(30);
        properties.getGeoMismatch().setMinDistanceKm(10);
        GeoMismatchRule custom = new GeoMismatchRule(properties);
        Transaction previous = tx("Austin", AUSTIN, NOW.minusHours(1));   // 47 km in 1h: 47 km/h

        RuleResult result = custom.evaluate(contextAt("San Marcos", SAN_MARCOS, NOW, List.of(previous)));

        assertThat(result.triggered()).isTrue();
        assertThat(result.points()).isEqualTo(55);
        assertThat(result.reason()).endsWith("above the 30 km/h limit");
    }

    @Test
    void haversineMatchesAKnownDistance() {
        // Paris to London is about 344 km.
        assertThat(GeoMismatchRule.distanceKm(48.8566, 2.3522, 51.5074, -0.1278)).isCloseTo(344, within(3.4));
        assertThat(GeoMismatchRule.distanceKm(30.27, -97.74, 30.27, -97.74)).isZero();
    }

    private static Transaction tx(String city, double[] location, OffsetDateTime time) {
        return new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("10.00"), "Shop", "GROCERY", city,
                location[0], location[1], time, 0, Decision.APPROVE);
    }

    private static RuleContext contextAt(String city, double[] location, OffsetDateTime time,
                                         List<Transaction> history) {
        return new RuleContext("card-1", new BigDecimal("10.00"), "Shop", "GROCERY", city,
                location[0], location[1], time, limit -> history, window -> OptionalInt.of(1));
    }
}
