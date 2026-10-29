package com.gopi.fraud.rules;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Transaction;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** "Impossible travel": the card moved faster than a person could between this and its previous transaction. */
@Component
public class GeoMismatchRule implements FraudRule {

    public static final String NAME = "GEO_MISMATCH";

    /** Mean Earth radius (IUGG). */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final FraudProperties.GeoMismatch config;

    public GeoMismatchRule(FraudProperties properties) {
        this.config = properties.getGeoMismatch();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        // The context's history excludes BLOCK transactions, so a blocked attempt is not a reference point.
        List<Transaction> history = ctx.recentHistory().apply(1);
        if (history.isEmpty()) {
            return RuleResult.notTriggered(NAME);
        }
        Transaction previous = history.get(0);

        double km = distanceKm(previous.getLatitude(), previous.getLongitude(), ctx.latitude(), ctx.longitude());
        if (km < config.getMinDistanceKm()) {
            return RuleResult.notTriggered(NAME);
        }

        // Own timestamps, not arrival order: the previous transaction may be later than this one.
        Duration elapsed = Duration.between(previous.getTransactionTime(), ctx.timestamp()).abs();
        if (elapsed.isZero()) {
            return RuleResult.triggered(NAME, config.getPoints(), String.format(Locale.ROOT,
                    "Travelled %,.0f km from %s to %s at the same instant; the limit is %,.0f km/h",
                    km, previous.getCity(), ctx.city(), config.getMaxSpeedKmh()));
        }

        double kmh = km / (elapsed.toMillis() / 3_600_000.0);
        if (kmh > config.getMaxSpeedKmh()) {
            return RuleResult.triggered(NAME, config.getPoints(), String.format(Locale.ROOT,
                    "Travelled %,.0f km from %s to %s in %s (%,.0f km/h), above the %,.0f km/h limit",
                    km, previous.getCity(), ctx.city(), format(elapsed), kmh, config.getMaxSpeedKmh()));
        }
        return RuleResult.notTriggered(NAME);
    }

    /** Great-circle (haversine) distance in km. */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    /** e.g. "1h 10m", "25m", "40s". */
    private static String format(Duration elapsed) {
        long seconds = elapsed.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
